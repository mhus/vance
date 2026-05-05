package de.mhus.vance.foot.script;

import org.jspecify.annotations.Nullable;

/**
 * Identity carried into a foot-side script run. Built by the calling
 * tool (typically {@code ClientJavascriptTool}) from the WebSocket
 * envelope's correlation id and the foot's currently bound session.
 *
 * <p>Brain-side concepts (tenant, quota bucket) are deliberately
 * absent — the foot doesn't know them.
 */
public record ClientExecutionContext(
        String requestId,
        @Nullable String sessionId,
        @Nullable String projectId) {
}
