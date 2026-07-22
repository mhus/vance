package de.mhus.vance.foot.script;

import java.util.Map;
import org.graalvm.polyglot.HostAccess;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Host-API surface exposed to foot-side JavaScript as the {@code client}
 * binding. Strictly local: only client-tools registered with
 * {@link de.mhus.vance.foot.tools.ClientToolService} are callable —
 * server tools and brain-side process concepts are not visible here.
 */
public final class ClientScriptApi {

    private static final Logger LOG = LoggerFactory.getLogger("client.script");

    @HostAccess.Export
    public final ScriptToolsApi tools;

    @HostAccess.Export
    public final ScriptContextView context;

    @HostAccess.Export
    public final ScriptLog log;

    public ClientScriptApi(ClientScriptApi.GatedToolInvoker tools, ClientExecutionContext ctx) {
        this.tools = new ScriptToolsApi(tools);
        this.context = new ScriptContextView(ctx);
        this.log = new ScriptLog(ctx);
    }

    /**
     * Gated invocation callback the executor passes in — wraps
     * {@code ClientToolService.invokeFromScript}, which enforces the
     * {@code ClientSecurityService} permission gate. The script bridge is
     * deliberately given only this gated entry point, never a raw
     * {@code ClientTool} bean, so {@code client.tools.call(...)} cannot
     * run client tools around the sandbox (code-review B5).
     */
    @FunctionalInterface
    public interface GatedToolInvoker {
        Map<String, Object> invoke(String name, @Nullable Map<String, Object> params);
    }

    /** Tool-dispatch surface exposed as {@code client.tools}. */
    public static final class ScriptToolsApi {

        private final GatedToolInvoker invoker;

        ScriptToolsApi(GatedToolInvoker invoker) {
            this.invoker = invoker;
        }

        @HostAccess.Export
        public Map<String, Object> call(String name, @Nullable Map<String, Object> params) {
            try {
                Map<String, Object> result = invoker.invoke(name, params);
                return result == null ? Map.of() : result;
            } catch (RuntimeException e) {
                throw new ScriptHostException(
                        "Client tool '" + name + "' failed: " + e.getMessage(), e);
            }
        }
    }

    /** Read-only client context exposed as {@code client.context}. */
    public static final class ScriptContextView {

        @HostAccess.Export
        public final String requestId;

        @HostAccess.Export
        public final @Nullable String sessionId;

        @HostAccess.Export
        public final @Nullable String projectId;

        ScriptContextView(ClientExecutionContext ctx) {
            this.requestId = ctx.requestId();
            this.sessionId = ctx.sessionId();
            this.projectId = ctx.projectId();
        }
    }

    /** Logger exposed as {@code client.log}. */
    public static final class ScriptLog {

        private final ClientExecutionContext ctx;

        ScriptLog(ClientExecutionContext ctx) {
            this.ctx = ctx;
        }

        @HostAccess.Export
        public void info(String message, @Nullable Map<String, Object> fields) {
            LOG.info("[client-script] req={} session={} {} {}",
                    ctx.requestId(), ctx.sessionId(), message,
                    fields == null ? Map.of() : fields);
        }

        @HostAccess.Export
        public void warn(String message, @Nullable Map<String, Object> fields) {
            LOG.warn("[client-script] req={} session={} {} {}",
                    ctx.requestId(), ctx.sessionId(), message,
                    fields == null ? Map.of() : fields);
        }

        @HostAccess.Export
        public void error(String message, @Nullable Map<String, Object> fields) {
            LOG.error("[client-script] req={} session={} {} {}",
                    ctx.requestId(), ctx.sessionId(), message,
                    fields == null ? Map.of() : fields);
        }
    }

    /** Host-side exception surfaced to JS as a regular Error. */
    public static final class ScriptHostException extends RuntimeException {
        public ScriptHostException(String message, @Nullable Throwable cause) {
            super(message, cause);
        }
    }
}
