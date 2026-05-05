package de.mhus.vance.brain.script;

import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import java.util.Map;
import org.graalvm.polyglot.HostAccess;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Host-API surface exposed to brain-side JavaScript as the {@code vance}
 * binding. Reachable members are annotated {@link HostAccess.Export};
 * the script context is configured to allow nothing else.
 *
 * <p>Identity (tenant/project/session/process) comes from the bound
 * {@link ContextToolsApi}, never from script-supplied parameters. A
 * script cannot escape its scope by passing a different tenant or
 * project to a tool call.
 */
public final class VanceScriptApi {

    private static final Logger LOG = LoggerFactory.getLogger("vance.script");

    @HostAccess.Export
    public final ScriptToolsApi tools;

    @HostAccess.Export
    public final ScriptContextView context;

    @HostAccess.Export
    public final ScriptLog log;

    @HostAccess.Export
    public final ScriptProcessApi process;

    public VanceScriptApi(ContextToolsApi toolsApi) {
        this.tools = new ScriptToolsApi(toolsApi);
        this.context = new ScriptContextView(toolsApi.scope());
        this.log = new ScriptLog(toolsApi.scope());
        this.process = new ScriptProcessApi(this);
    }

    /** Tool-dispatch surface exposed as {@code vance.tools}. */
    public static final class ScriptToolsApi {

        private final ContextToolsApi delegate;

        ScriptToolsApi(ContextToolsApi delegate) {
            this.delegate = delegate;
        }

        @HostAccess.Export
        public Map<String, Object> call(String name, @Nullable Map<String, Object> params) {
            try {
                return delegate.invoke(name, params == null ? Map.of() : params);
            } catch (ToolException e) {
                throw new ScriptHostException(e.getMessage(), e);
            } catch (RuntimeException e) {
                throw new ScriptHostException(
                        "Tool '" + name + "' failed: " + e.getMessage(), e);
            }
        }
    }

    /** Read-only scope info exposed as {@code vance.context}. */
    public static final class ScriptContextView {

        @HostAccess.Export
        public final String tenantId;

        @HostAccess.Export
        public final @Nullable String projectId;

        @HostAccess.Export
        public final @Nullable String sessionId;

        @HostAccess.Export
        public final @Nullable String processId;

        @HostAccess.Export
        public final @Nullable String userId;

        ScriptContextView(ToolInvocationContext scope) {
            this.tenantId = scope.tenantId();
            this.projectId = scope.projectId();
            this.sessionId = scope.sessionId();
            this.processId = scope.processId();
            this.userId = scope.userId();
        }
    }

    /** Structured logger exposed as {@code vance.log}. */
    public static final class ScriptLog {

        private final ToolInvocationContext scope;

        ScriptLog(ToolInvocationContext scope) {
            this.scope = scope;
        }

        @HostAccess.Export
        public void info(String message, @Nullable Map<String, Object> fields) {
            LOG.info("[script] tenant={} project={} process={} {} {}",
                    scope.tenantId(), scope.projectId(), scope.processId(),
                    message, fields == null ? Map.of() : fields);
        }

        @HostAccess.Export
        public void warn(String message, @Nullable Map<String, Object> fields) {
            LOG.warn("[script] tenant={} project={} process={} {} {}",
                    scope.tenantId(), scope.projectId(), scope.processId(),
                    message, fields == null ? Map.of() : fields);
        }

        @HostAccess.Export
        public void error(String message, @Nullable Map<String, Object> fields) {
            LOG.error("[script] tenant={} project={} process={} {} {}",
                    scope.tenantId(), scope.projectId(), scope.processId(),
                    message, fields == null ? Map.of() : fields);
        }
    }

    /**
     * Process surface exposed as {@code vance.process}. v1 only forwards
     * to the {@code process_create} tool — direct event-send is not
     * routed because no matching tool exists yet.
     */
    public static final class ScriptProcessApi {

        private final VanceScriptApi parent;

        ScriptProcessApi(VanceScriptApi parent) {
            this.parent = parent;
        }

        @HostAccess.Export
        public Map<String, Object> spawn(Map<String, Object> params) {
            return parent.tools.call("process_create", params);
        }
    }

    /** Host-side exception surfaced to JS as a regular Error. */
    public static final class ScriptHostException extends RuntimeException {
        public ScriptHostException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
