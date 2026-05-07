package de.mhus.vance.foot.tools;

import de.mhus.vance.api.tools.ToolSpec;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.Map;

/**
 * Foot-side specialisation of {@link Tool}. Two responsibilities:
 *
 * <ul>
 *   <li>A simpler 1-arg {@link #invoke(Map)} that the foot tool
 *       implementations actually use — Foot is single-tenant /
 *       single-user / single-session per JVM, so the
 *       {@link ToolInvocationContext} arg from the unified Tool
 *       interface adds nothing useful.</li>
 *   <li>Default {@link #toSpec()} that pins {@code source="client"}
 *       so the brain-side {@code ClientToolSource} routes invocations
 *       back to the originating session.</li>
 * </ul>
 *
 * <p>Implementations are stateless Spring components picked up by
 * {@link ClientToolService}. {@link #invoke} runs synchronously on
 * the WebSocket-receive thread; long-running work should hand off
 * to a background executor and return a job-id (see
 * {@code ClientExecutorService} for the pattern).
 *
 * <p>Server-side dispatcher receives the wire-{@link ToolSpec}, treats
 * the foot tool exactly like a server bean, and the foot's
 * {@code ClientToolService.dispatch} bridges the brain-issued
 * invocation back to the local {@link #invoke(Map)} method.
 */
public interface ClientTool extends Tool {

    /**
     * Foot-side single-arg invoke. Implementations override this; the
     * 2-arg variant inherited from {@link Tool} bridges to it.
     */
    Map<String, Object> invoke(Map<String, Object> params);

    @Override
    default Map<String, Object> invoke(
            Map<String, Object> params, ToolInvocationContext ctx) {
        return invoke(params);
    }

    /** Convenience: foot-side wire-spec with {@code source="client"} pinned. */
    default ToolSpec toSpec() {
        return toSpec("client");
    }
}
