package de.mhus.vance.foot.connection.handlers;

import de.mhus.vance.api.tools.ClientToolInvokeRequest;
import de.mhus.vance.api.tools.ClientToolInvokeResponse;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.connection.MessageHandler;
import de.mhus.vance.foot.tools.ClientToolService;
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

    public ClientToolInvokeHandler(
            ClientToolService clientTools,
            ObjectProvider<ConnectionService> connectionProvider) {
        this.clientTools = clientTools;
        this.connectionProvider = connectionProvider;
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
        long t0 = System.currentTimeMillis();
        ClientToolInvokeResponse response = clientTools.dispatch(
                request.getCorrelationId(), request.getName(), request.getParams());
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
