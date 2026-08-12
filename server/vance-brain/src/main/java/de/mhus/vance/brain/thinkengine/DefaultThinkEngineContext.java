package de.mhus.vance.brain.thinkengine;

import de.mhus.vance.brain.ai.AiModelService;
import de.mhus.vance.brain.events.ClientEventPublisher;
import de.mhus.vance.brain.history.BufferingHistoryTagSink;
import de.mhus.vance.brain.history.HistoryTagBuilder;
import de.mhus.vance.brain.history.TurnReasoningBuffer;
import de.mhus.vance.brain.progress.ProgressEmitter;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.brain.tools.ToolResultStorage;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.brain.tools.ToolInvocationListener;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.llmtrace.LlmTraceService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.toolhealth.ToolHealthService;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import org.jspecify.annotations.Nullable;

/**
 * Straightforward {@link ThinkEngineContext} — carries the process and
 * wired services. Created per lifecycle call by
 * {@link ThinkEngineService}; {@code projectId} is resolved by the
 * service via the session lookup and threaded in here.
 *
 * <p>The {@code toolFilterResolver} is a callback re-evaluated on
 * every {@link #tools()} call so the per-turn {@code RecipeResolver.ToolFilter}
 * tracks the process's current {@code mode}. This matters for Plan-Mode
 * where a single {@code runTurn} can transition through multiple modes
 * (e.g. PLANNING → EXECUTING via {@code START_EXECUTION}); a snapshot
 * of the tool filter taken at context-build time would be stale for the
 * EXECUTING continuation turn.
 *
 * <p>{@code activationDecayTtl} is the TTL after which an activated
 * deferred tool decays back to the discovery block. {@link Duration#ZERO}
 * disables decay (entries persist until cleanup runs). The decay filter
 * is applied lazily on read; persistent cleanup happens via
 * {@link ThinkProcessService#cleanupDecayedActivations}.
 */
record DefaultThinkEngineContext(
        ThinkProcessDocument process,
        String projectId,
        @Nullable String userId,
        Set<String> baseAllowedTools,
        AiModelService aiModelService,
        SettingService settingService,
        ChatMessageService chatMessageService,
        ToolDispatcher toolDispatcher,
        ClientEventPublisher eventPublisher,
        ThinkProcessService thinkProcessService,
        ProcessEventEmitter processEventEmitter,
        ProgressEmitter progressEmitter,
        BiFunction<de.mhus.vance.api.thinkprocess.ProcessMode, ToolInvocationContext, RecipeResolver.ToolFilter> toolFilterResolver,
        ToolInvocationListener toolInvocationListener,
        Duration activationDecayTtl,
        boolean traceLlm,
        LlmTraceService llmTraceService,
        HistoryTagBuilder historyTagBuilder,
        BufferingHistoryTagSink historyTagSink,
        TurnReasoningBuffer reasoning,
        @Nullable ToolResultStorage toolResultStorage,
        ToolHealthService toolHealthService,
        Set<String> engineRoles,
        de.mhus.vance.brain.ai.attachment.@Nullable ToolImageHarvester imageHarvester,
        de.mhus.vance.brain.ai.attachment.ToolAttachmentSink attachmentSink,
        /**
         * Resolves the endpoint's {@code tools}-array cap plus the ranking
         * signals for the budget stage in {@code classify}. {@code null}
         * disables the budget entirely (test contexts). See
         * {@code planning/tool-surface-budget.md}.
         */
        de.mhus.vance.brain.tools.budget.@Nullable ToolBudgetService toolBudgetService
) implements ThinkEngineContext {

    @Override
    public String tenantId() {
        return process.getTenantId();
    }

    @Override
    public String sessionId() {
        return process.getSessionId();
    }

    @Override
    public @Nullable String workingProjectId() {
        String spot = process.getWorkingProjectId();
        if (spot == null || spot.isBlank()) return null;
        return spot;
    }

    @Override
    public ContextToolsApi tools() {
        ToolInvocationContext scope = new ToolInvocationContext(
                process.getTenantId(),
                projectId,
                process.getSessionId(),
                process.getId(),
                userId,
                workingProjectId());
        // Re-resolve the tool filter every call against the process's
        // current mode — see class doc for why a snapshot won't do.
        RecipeResolver.ToolFilter filter = toolFilterResolver.apply(process.getMode(), scope);
        Map<String, Instant> activations = liveActivations();
        Set<String> activated = activations.keySet();
        // Budget stage inputs. Resolving the cap reads the model-alias
        // cascade, so ToolBudgetService memoises it — tools() runs once per
        // action-loop iteration, not once per turn.
        de.mhus.vance.brain.tools.budget.ToolBudget budget = null;
        de.mhus.vance.brain.tools.budget.ToolTriage.Hints familyHints = null;
        if (toolBudgetService != null) {
            budget = toolBudgetService.forProcess(process, projectId, activations);
            familyHints = toolBudgetService.familyHints();
        }
        ContextToolsApi.Classification c = ContextToolsApi.classify(
                toolDispatcher, scope, baseAllowedTools, filter, activated,
                process.getBoundProfile(),
                engineRoles,
                budget, familyHints);
        // Sliding-TTL refresh: when the LLM invokes an activated
        // deferred tool, bump its timestamp so frequent use beats decay.
        java.util.function.Consumer<String> refresh = name ->
                thinkProcessService.activateDeferredTool(process.getId(), name);
        return new ContextToolsApi(
                toolDispatcher, scope,
                c.allowed(), c.primary(), c.deferred(), c.activatedDeferred(),
                toolInvocationListener, refresh,
                historyTagBuilder, historyTagSink,
                toolResultStorage,
                toolHealthService,
                imageHarvester,
                attachmentSink)
                // Carry the budget so a later withAdditional (skill tools
                // land in primary after the classification) re-fits
                // instead of overflowing the endpoint's cap.
                .withBudget(budget, familyHints);
    }

    /**
     * Reads the {@code activatedDeferredTools} map fresh from Mongo and
     * applies the TTL decay filter. Timestamps are kept, not just the
     * names: activation recency is the budget stage's strongest ranking
     * signal (see {@code planning/tool-surface-budget.md} §4). Sourcing this from the DB (not from
     * the in-memory {@code process} snapshot) lets within-turn
     * activations from {@code tool_description} take effect on the very
     * next {@link #tools()} call — the action-loop refreshes its
     * {@link ContextToolsApi} after each iteration that invoked read
     * tools. Stale entries (timestamp older than now − ttl) are
     * dropped from the returned set; persistent cleanup is the
     * caller's job (see {@link ThinkProcessService#cleanupDecayedActivations}).
     * Zero TTL disables decay.
     */
    private Map<String, Instant> liveActivations() {
        Map<String, Instant> map = thinkProcessService.getActivatedDeferredTools(process.getId());
        if (map == null || map.isEmpty()) return Map.of();
        if (activationDecayTtl == null || activationDecayTtl.isZero() || activationDecayTtl.isNegative()) {
            return Map.copyOf(map);
        }
        Instant cutoff = Instant.now().minus(activationDecayTtl);
        Map<String, Instant> out = new LinkedHashMap<>();
        for (Map.Entry<String, Instant> e : map.entrySet()) {
            if (e.getValue() != null && e.getValue().isAfter(cutoff)) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return Map.copyOf(out);
    }

    @Override
    public ClientEventPublisher events() {
        return eventPublisher;
    }

    @Override
    public List<SteerMessage> drainPending() {
        return SteerMessageCodec.toMessages(
                thinkProcessService.drainPending(process.getId()));
    }

    @Override
    public ProcessOrchestrator processes() {
        return new DefaultProcessOrchestrator(
                process, thinkProcessService, processEventEmitter);
    }

    @Override
    public void emitReply(
            String content,
            @Nullable Instant inResponseToAt,
            @Nullable Map<String, Object> payload) {
        progressEmitter.emitReply(process, content, inResponseToAt, payload);
    }

    @Override
    public void emitInterimReply(String content, @Nullable Instant inResponseToAt) {
        progressEmitter.emitInterimReply(process, content, inResponseToAt);
    }
}
