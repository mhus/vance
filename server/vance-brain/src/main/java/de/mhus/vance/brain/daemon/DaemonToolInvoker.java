package de.mhus.vance.brain.daemon;

import de.mhus.vance.api.tools.ClientToolInvokeRequest;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.brain.ws.WebSocketSender;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import de.mhus.vance.toolpack.ToolException;

/**
 * Single seam for invoking a tool on a named {@code profile=daemon}
 * Foot and blocking on its {@code client-tool-result}. Shared by every
 * caller that routes over the {@link DaemonRegistry}:
 *
 * <ul>
 *   <li>{@link FootDaemonToolFactory} — explicit {@code foot_daemon}
 *       ServerTool sub-tools ({@code <pack>__<subTool>}).</li>
 *   <li>{@code WorkTargetDispatcher} — generic {@code file_*} /
 *       {@code exec_*} wrappers whose process points at a
 *       {@link de.mhus.vance.shared.worktarget.WorkTargetKind#DAEMON}
 *       target; the {@code client_*} backend name is sent verbatim
 *       (it matches the daemon's announced manifest entry).</li>
 * </ul>
 *
 * <p>Re-resolves the daemon at call time so a daemon that disappeared
 * between materialise and invoke produces a fresh, accurate error
 * instead of writing to a dead WebSocket. The protocol on the wire is
 * identical to the session-bound client-tool path
 * ({@code CLIENT_TOOL_INVOKE} → {@code CLIENT_TOOL_RESULT}); only the
 * pending lifecycle lives on the {@link DaemonRegistry} (correlation
 * prefix {@code dt-}) rather than the session's
 * {@code ClientToolRegistry} ({@code ct-}).
 */
@Component
@RequiredArgsConstructor
public class DaemonToolInvoker {

    private final DaemonRegistry daemonRegistry;
    private final WebSocketSender sender;

    /**
     * Invokes {@code toolName} on the daemon identified by {@code key},
     * blocking up to {@code timeout}.
     *
     * @throws ToolException when the daemon is offline/stale, the
     *         invoke envelope can't be written, the call times out or
     *         the daemon reports an error.
     */
    public Map<String, Object> invoke(
            DaemonRegistry.DaemonKey key,
            String toolName,
            @Nullable Map<String, Object> params,
            Duration timeout) {
        var refOpt = daemonRegistry.find(key);
        if (refOpt.isEmpty()) {
            throw new ToolException("daemon '" + key.daemonName()
                    + "' is offline in project '" + key.projectId() + "'");
        }
        var ref = refOpt.get();
        if (ref.stale()) {
            String since = ref.disconnectedAt() == null
                    ? "recently" : "since " + ref.disconnectedAt();
            throw new ToolException("daemon '" + key.daemonName()
                    + "' is offline " + since
                    + " — still listed but unreachable until reconnect");
        }
        DaemonRegistry.DaemonPending pending = daemonRegistry.beginInvocation(key, toolName);
        try {
            ClientToolInvokeRequest invoke = ClientToolInvokeRequest.builder()
                    .correlationId(pending.correlationId())
                    .name(toolName)
                    .params(params == null ? Map.of() : params)
                    .build();
            sender.sendNotification(ref.wsSession(), MessageType.CLIENT_TOOL_INVOKE, invoke);
        } catch (IOException ioe) {
            daemonRegistry.cancel(pending.correlationId(),
                    "failed to write invoke envelope: " + ioe.getMessage());
            throw new ToolException("daemon invoke send failed: " + ioe.getMessage(), ioe);
        }
        try {
            return pending.future().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            daemonRegistry.cancel(pending.correlationId(),
                    "daemon '" + key.daemonName() + "' timed out after " + timeout);
            throw new ToolException("daemon '" + key.daemonName()
                    + "' did not respond within " + timeout, te);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            daemonRegistry.cancel(pending.correlationId(), "invoke interrupted");
            throw new ToolException("daemon invoke interrupted", ie);
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause() == null ? ee : ee.getCause();
            throw new ToolException(cause.getMessage() == null
                    ? "daemon invoke failed" : cause.getMessage(), cause);
        }
    }
}
