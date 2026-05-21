package de.mhus.vance.brain.hooks;

import de.mhus.vance.brain.magrathea.MagratheaWorkflowService;
import de.mhus.vance.shared.magrathea.MagratheaWorkflowParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.graalvm.polyglot.HostAccess;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Workflow write channel exposed as {@code workflows.*} in JS hooks
 * (and via the {@code workflow.start} action verb for LLM hooks).
 *
 * <p>The hook supplies a workflow name and an optional params map.
 * The dispatcher pins {@code tenantId}/{@code projectId} from the
 * hook's binding scope — scripts cannot cross-tenant or cross-project
 * spawn. The triggering hook's name appears in {@code startedBy} so
 * the workflow run's audit trail attributes back to the hook.
 *
 * <p>Only present when {@code vance.services.magrathea=true}. When
 * Magrathea is disabled, every call returns {@code null} and logs WARN;
 * the dispatcher still constructs the client so the JS surface stays
 * stable, but the channel is a no-op.
 */
public final class HookWorkflowClient {

    private static final Logger LOG = LoggerFactory.getLogger("vance.hooks.workflows");

    private final @Nullable MagratheaWorkflowService workflowService;
    private final String tenantId;
    private final String projectId;
    private final String hookName;

    public HookWorkflowClient(
            @Nullable MagratheaWorkflowService workflowService,
            String tenantId,
            String projectId,
            String hookName) {
        this.workflowService = workflowService;
        this.tenantId = tenantId;
        this.projectId = projectId;
        this.hookName = hookName;
    }

    /**
     * Start a Magrathea workflow run. Returns the {@code workflowRunId}
     * for follow-up tracking, or {@code null} when the call failed
     * (cause is logged).
     *
     * <p>JS: {@code const runId = workflows.start("pr-review", {pr_url: "..."});}
     */
    @HostAccess.Export
    public @Nullable String start(String name, @Nullable Map<String, Object> params) {
        if (workflowService == null) {
            LOG.warn("Hook '{}/{}' workflows.start('{}') skipped — Magrathea service is not active",
                    tenantId, hookName, name);
            return null;
        }
        if (name == null || name.isBlank()) {
            LOG.warn("Hook '{}/{}' workflows.start called with blank name", tenantId, hookName);
            return null;
        }
        try {
            String startedBy = "hook:" + hookName;
            String runId = workflowService.start(
                    tenantId, projectId, name, params, startedBy);
            LOG.info("Hook '{}/{}' started workflow '{}' runId='{}'",
                    tenantId, hookName, name, runId);
            return runId;
        } catch (MagratheaWorkflowService.MagratheaWorkflowException ex) {
            LOG.warn("Hook '{}/{}' workflows.start('{}') failed: workflow not found — {}",
                    tenantId, hookName, name, ex.getMessage());
            return null;
        } catch (MagratheaWorkflowParseException ex) {
            LOG.warn("Hook '{}/{}' workflows.start('{}') failed: workflow YAML invalid — {}",
                    tenantId, hookName, name, ex.getMessage());
            return null;
        } catch (RuntimeException ex) {
            LOG.warn("Hook '{}/{}' workflows.start('{}') errored: {}",
                    tenantId, hookName, name, ex.toString());
            return null;
        }
    }

    /**
     * Convenience overload: start without caller params. Equivalent
     * to {@code workflows.start(name, {})}.
     */
    @HostAccess.Export
    public @Nullable String start(String name) {
        return start(name, new LinkedHashMap<>());
    }
}
