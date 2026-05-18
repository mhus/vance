package de.mhus.vance.brain.action;

import de.mhus.vance.api.action.ScriptSource;
import de.mhus.vance.api.action.TriggerAction;
import de.mhus.vance.brain.script.ScriptExecutionException;
import de.mhus.vance.brain.script.ScriptExecutor;
import de.mhus.vance.brain.script.ScriptRequest;
import de.mhus.vance.brain.script.ScriptResult;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import de.mhus.vance.shared.workspace.WorkspaceException;
import de.mhus.vance.shared.workspace.WorkspaceService;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Execute a {@link TriggerAction.Script} via the shared
 * {@link ScriptExecutor}. Loads the script body from the configured
 * source (document cascade today; workspace coming in §6.1's separate
 * stage), runs it, and maps the return value via
 * {@link ScriptOutcomeMapper}.
 *
 * <p>Synchronous — blocks until the script completes or the wall-clock
 * timeout fires.
 *
 * <p>Tool-Surface: this executor passes a minimally-configured
 * {@link ContextToolsApi} with an <em>empty</em> allow-list. That makes
 * the v1 executor effectively trigger-scoped (read-only) regardless of
 * the caller — {@code vance.tools.call(...)} from a script will throw
 * because no tool is in the allow set. Process-scoped script execution
 * with a real tool surface is wired in via the
 * {@code @SpawnTool}/{@code ScopeLevel} pass (Stufe 2f); until then,
 * callers that want tool access still go through their existing
 * {@code SkillScriptTool}/{@code ScriptedToolFactory} paths.
 */
@Component
@Slf4j
public final class ScriptActionExecutor implements ActionExecutor<TriggerAction.Script> {

    /** Default wall-clock for a script run when the action did not specify one. */
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final ScriptExecutor scriptExecutor;
    private final DocumentService documentService;
    private final ToolDispatcher toolDispatcher;
    private final @Nullable WorkspaceService workspaceService;

    public ScriptActionExecutor(ScriptExecutor scriptExecutor,
                                DocumentService documentService,
                                ToolDispatcher toolDispatcher,
                                @org.springframework.beans.factory.annotation.Autowired(required = false)
                                @Nullable WorkspaceService workspaceService) {
        this.scriptExecutor = scriptExecutor;
        this.documentService = documentService;
        this.toolDispatcher = toolDispatcher;
        this.workspaceService = workspaceService;
    }

    /** Backwards-compat ctor — workspace source unavailable. */
    public ScriptActionExecutor(ScriptExecutor scriptExecutor,
                                DocumentService documentService,
                                ToolDispatcher toolDispatcher) {
        this(scriptExecutor, documentService, toolDispatcher, null);
    }

    @Override
    public Class<TriggerAction.Script> actionType() {
        return TriggerAction.Script.class;
    }

    @Override
    public ActionResult execute(ActionInvocation<TriggerAction.Script> invocation) {
        TriggerAction.Script action = invocation.action();
        TriggerContext ctx = invocation.context();

        String code = switch (action.source()) {
            case DOCUMENT -> loadFromDocument(ctx.tenantId(), ctx.projectId(), action.path());
            case WORKSPACE -> loadFromWorkspace(ctx, action);
        };
        if (code == null) {
            return ScriptOutcomeMapper.scriptNotFound(action.path());
        }
        if (code.isEmpty()) {
            return ActionResult.failure(
                    ActionOutcome.TECHNICAL_ERROR,
                    "script body is empty: " + action.path(),
                    Map.of("error", "empty-script:" + action.path()));
        }

        ContextToolsApi tools = buildToolsSurface(ctx);
        Duration timeout = timeoutFor(action);
        Map<String, @Nullable Object> bindings = bindingsFor(action);
        String sourceName = sourceLabel(action);

        ScriptResult result;
        try {
            result = scriptExecutor.run(new ScriptRequest(
                    "js",
                    code,
                    sourceName,
                    tools,
                    timeout,
                    bindings,
                    /*recipeName*/ null,
                    invocation.triggerKind().isProcessScoped()
                            ? ScopeLevel.PROCESS_SCOPED
                            : ScopeLevel.TRIGGER_SCOPED));
        } catch (ScriptExecutionException e) {
            log.debug("ScriptActionExecutor: script '{}' failed: errorClass={} message={}",
                    sourceName, e.errorClass(), e.getMessage());
            return ScriptOutcomeMapper.mapException(e);
        } catch (RuntimeException e) {
            log.warn("ScriptActionExecutor: unexpected RuntimeException running '{}': {}",
                    sourceName, e.toString());
            return ActionResult.failure(
                    ActionOutcome.TECHNICAL_ERROR,
                    "unexpected: " + e.getMessage(),
                    Map.of("error", "unexpected:" + e.getClass().getSimpleName()));
        }

        log.debug("ScriptActionExecutor: script '{}' completed in {}ms",
                sourceName, result.duration().toMillis());
        return ScriptOutcomeMapper.mapValue(result.value());
    }

    // ──────────────────── Source loaders ────────────────────

    private @Nullable String loadFromDocument(String tenantId, String projectId, String path) {
        Optional<LookupResult> hit = documentService.lookupCascade(tenantId, projectId, path);
        return hit.map(LookupResult::content).orElse(null);
    }

    private @Nullable String loadFromWorkspace(TriggerContext ctx, TriggerAction.Script action) {
        if (workspaceService == null) {
            log.warn("ScriptActionExecutor: workspace source requested but WorkspaceService "
                            + "is not active — tenant='{}' project='{}' dirName='{}' path='{}'",
                    ctx.tenantId(), ctx.projectId(), action.dirName(), action.path());
            return null;
        }
        Path resolved;
        try {
            resolved = workspaceService.readablePath(
                    ctx.tenantId(), ctx.projectId(), action.dirName(), action.path());
        } catch (WorkspaceException ex) {
            log.warn("ScriptActionExecutor: workspace lookup failed for {}/{}/{}: {}",
                    ctx.tenantId(), ctx.projectId(), action.dirName() + "/" + action.path(),
                    ex.getMessage());
            return null;
        }
        try {
            return Files.readString(resolved, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            log.warn("ScriptActionExecutor: workspace read failed for {}: {}",
                    resolved, ex.getMessage());
            return null;
        }
    }

    // ──────────────────── Helpers ────────────────────

    private ContextToolsApi buildToolsSurface(TriggerContext ctx) {
        ToolInvocationContext inv = new ToolInvocationContext(
                ctx.tenantId(),
                ctx.projectId(),
                ctx.parentSessionId(),
                ctx.parentProcessId(),
                ctx.resolvedRunAs());
        // Empty allow-set for now — full tool surface comes with the
        // ScopeLevel/SpawnTool pass (§8, Stufe 2f).
        return new ContextToolsApi(toolDispatcher, inv);
    }

    private static Duration timeoutFor(TriggerAction.Script action) {
        return action.timeoutSeconds() == null
                ? DEFAULT_TIMEOUT
                : Duration.ofSeconds(action.timeoutSeconds());
    }

    private static Map<String, @Nullable Object> bindingsFor(TriggerAction.Script action) {
        // The caller's params land as a top-level `args` variable — same
        // convention as the SkillScriptTool, so script authors only learn
        // one binding pattern.
        Map<String, @Nullable Object> bindings = new LinkedHashMap<>();
        bindings.put("args", action.params() == null ? Map.of() : action.params());
        return bindings;
    }

    private static String sourceLabel(TriggerAction.Script action) {
        if (action.source() == ScriptSource.DOCUMENT) {
            return "doc:" + action.path();
        }
        return "workspace:"
                + StringUtils.defaultString(action.dirName(), "?")
                + "/" + action.path();
    }
}
