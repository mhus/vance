package de.mhus.vance.foot.connection.handlers;

import de.mhus.vance.api.tools.ClientToolInvokeRequest;
import de.mhus.vance.api.tools.ClientToolInvokeResponse;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.connection.MessageHandler;
import de.mhus.vance.foot.tools.ClientToolService;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Receives {@code client-tool-invoke} envelopes from the brain,
 * delegates to {@link ClientToolService#dispatch}, and ships the
 * result back as a {@link MessageType#CLIENT_TOOL_RESULT}
 * notification. The brain matches results by {@code correlationId};
 * we never throw out of {@link #handle} — silence would block the
 * brain's tool loop for 30 seconds.
 *
 * <p>{@link ConnectionService} is injected lazily through
 * {@link ObjectProvider} so this handler can be discovered by
 * {@link de.mhus.vance.foot.connection.MessageDispatcher} (which
 * {@code ConnectionService} itself depends on) without forming a
 * construction-time cycle.
 */
@Component
@Slf4j
public class ClientToolInvokeHandler implements MessageHandler {

    private final ClientToolService clientTools;
    private final ObjectProvider<ConnectionService> connectionProvider;
    private final ObjectMapper json = JsonMapper.builder().build();

    /**
     * Tool execution runs here, OFF the WebSocket receive thread. A
     * {@code client_exec_run} can be a multi-minute build; running it
     * inline in {@link #handle} (called from the JDK WebSocket's
     * {@code onText}) would block the receive path so the socket never
     * requests the next frame — inbound PONGs then pile up unread and
     * the keep-alive trips a false "connection dead" reconnect that
     * drops the in-flight tool. Daemon threads so a stuck exec never
     * blocks JVM shutdown.
     */
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "vance-foot-client-tool");
        t.setDaemon(true);
        return t;
    });

    public ClientToolInvokeHandler(
            ClientToolService clientTools,
            ObjectProvider<ConnectionService> connectionProvider) {
        this.clientTools = clientTools;
        this.connectionProvider = connectionProvider;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    @Override
    public String messageType() {
        return MessageType.CLIENT_TOOL_INVOKE;
    }

    @Override
    public void handle(WebSocketEnvelope envelope) {
        ClientToolInvokeRequest request;
        try {
            request = json.convertValue(envelope.getData(), ClientToolInvokeRequest.class);
        } catch (RuntimeException e) {
            log.warn("client-tool-invoke: bad payload: {}", e.toString());
            return;
        }
        if (request == null || request.getCorrelationId() == null
                || request.getName() == null) {
            log.warn("client-tool-invoke: missing correlationId or name");
            return;
        }
        log.info("client-tool-invoke recv tool='{}' correlation='{}'",
                request.getName(), request.getCorrelationId());
        // Return immediately so the WS receive thread frees up; the tool
        // runs on the executor and ships its result when done.
        executor.submit(() -> runAndReply(request));
    }

    private void runAndReply(ClientToolInvokeRequest request) {
        long t0 = System.currentTimeMillis();
        ClientToolInvokeResponse response;
        try {
            response = clientTools.dispatch(
                    request.getCorrelationId(), request.getName(), request.getParams());
        } catch (RuntimeException e) {
            // dispatch is expected to fold failures into the response, but
            // stay defensive so a bug can't kill the worker thread silently
            // (the brain's tool loop would then hang until its own timeout).
            log.warn("client-tool dispatch threw tool='{}' correlation='{}': {}",
                    request.getName(), request.getCorrelationId(), e.toString());
            return;
        }
        long elapsed = System.currentTimeMillis() - t0;
        ConnectionService connection = connectionProvider.getIfAvailable();
        if (connection == null) {
            log.warn("client-tool-invoke: no ConnectionService available "
                    + "— result for correlation='{}' dropped after {}ms",
                    request.getCorrelationId(), elapsed);
            return;
        }
        boolean sent = connection.send(WebSocketEnvelope.notification(
                MessageType.CLIENT_TOOL_RESULT, response));
        log.info("client-tool-result send tool='{}' correlation='{}' sent={} elapsed={}ms{}",
                request.getName(), request.getCorrelationId(), sent, elapsed,
                response.getError() == null ? "" : " error='" + response.getError() + "'");
    }
}
