package de.mhus.vance.brain.thinkengine.plan;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.inbox.Criticality;
import de.mhus.vance.api.inbox.InboxItemType;
import de.mhus.vance.api.thinkprocess.ProcessMode;
import de.mhus.vance.api.thinkprocess.TodoItem;
import de.mhus.vance.api.thinkprocess.TodoStatus;
import de.mhus.vance.brain.arthur.PlanModeEventEmitter;
import de.mhus.vance.brain.memory.RecompactionTags;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.brain.thinkengine.action.EngineAction;
import de.mhus.vance.brain.thinkengine.action.StructuredActionEngine.ActionTurnOutcome;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.inbox.InboxItemDocument;
import de.mhus.vance.shared.inbox.InboxItemService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Engine-agnostic dispatcher and handlers for the four Plan-Mode
 * actions (START_PLAN / PROPOSE_PLAN / START_EXECUTION / TODO_UPDATE).
 * Engines that support Plan-Mode call {@link #dispatch} at the start of
 * their action-loop; when the action is Plan-Mode the service returns
 * the {@link ActionTurnOutcome}, otherwise {@code null} and the engine
 * handles it itself.
 *
 * <p>The logic in here was originally inline in {@code ArthurEngine};
 * the extraction (see {@code planning/plan-mode-shared.md}) leaves
 * Arthur identical externally but lets Eddie (and future engines)
 * reuse the handlers without copy-paste.
 *
 * <p>Cross-package dependency: {@link PlanModeEventEmitter} still lives
 * under {@code de.mhus.vance.brain.arthur} for historical reasons but
 * is logically engine-agnostic. A later cleanup may move it into this
 * package.
 *
 * <p>History-tag emission (v2): the handlers push {@code MODE:plan},
 * {@code MODE:execute}, {@code PLAN_STEP_STARTED:<id>} and
 * {@code PLAN_STEP_DONE:<id>} into {@link ThinkEngineContext#historyTagSink()},
 * exactly like the inline Arthur implementation did before. Engines
 * keep their per-turn flush mechanic; no change needed there.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PlanModeService {

    /**
     * Minimum count of {@code USER}-role turns before the
     * {@code MODE:plan} marker for the recompaction-offer hook to fire.
     * Below that, the plan WAS the conversation — folding it away would
     * empty the chat. See {@code planning/topic-recompaction.md} §7.
     */
    private static final int MIN_PRE_PLAN_USER_TURNS = 2;

    private final ThinkProcessService thinkProcessService;
    private final PlanModeEventEmitter planModeEventEmitter;
    private final ChatMessageService chatMessageService;
    private final InboxItemService inboxItemService;

    /**
     * Engine entry point. Returns the outcome when {@code action} is a
     * Plan-Mode action; otherwise {@code null} so the engine can fall
     * through to its own switch.
     */
    public @Nullable ActionTurnOutcome dispatch(
            EngineAction action, ThinkProcessDocument process, ThinkEngineContext ctx) {
        if (action == null || !PlanModeActionSchema.isPlanModeAction(action.type())) {
            return null;
        }
        return switch (action.type()) {
            case PlanModeActionSchema.TYPE_START_PLAN      -> handleStartPlan(action, process, ctx);
            case PlanModeActionSchema.TYPE_PROPOSE_PLAN    -> handleProposePlan(action, process, ctx);
            case PlanModeActionSchema.TYPE_START_EXECUTION -> handleStartExecution(action, process, ctx);
            case PlanModeActionSchema.TYPE_TODO_UPDATE     -> handleTodoUpdate(action, process, ctx);
            default -> null; // unreachable thanks to isPlanModeAction filter
        };
    }

    // ──────────────────── Action handlers ────────────────────

    /**
     * {@code START_PLAN} — flip the process into EXPLORING. Read-only
     * tool filter activates via the recipe-driven mode-cascade on the
     * next per-call context build.
     *
     * <p>Recipe property {@code planMode: disabled} blocks this action.
     * {@code planMode: auto} (default) and {@code planMode: required}
     * allow it.
     */
    ActionTurnOutcome handleStartPlan(
            EngineAction action, ThinkProcessDocument process, ThinkEngineContext ctx) {
        String planMode = paramString(process,
                PlanModeActionSchema.ENGINE_PARAM_PLAN_MODE,
                PlanModeActionSchema.PLAN_MODE_AUTO);
        if (PlanModeActionSchema.PLAN_MODE_DISABLED.equalsIgnoreCase(planMode)) {
            log.info("{}.START_PLAN id='{}' rejected — planMode=disabled",
                    engineName(process), process.getId());
            return new ActionTurnOutcome(
                    "(plan mode is disabled for this recipe — pick a different "
                            + "action: ANSWER, DELEGATE, ASK_USER, …)",
                    /*awaitingUserInput*/ false);
        }
        ProcessMode prior = process.getMode();
        boolean ok = thinkProcessService.updateMode(process.getId(), ProcessMode.EXPLORING);
        if (!ok) {
            log.warn("{}.START_PLAN id='{}' failed — process not found",
                    engineName(process), process.getId());
            return new ActionTurnOutcome(
                    "(internal: failed to enter plan mode)", true);
        }
        process.setMode(ProcessMode.EXPLORING);
        planModeEventEmitter.emitModeChanged(process, prior, ProcessMode.EXPLORING);
        log.info("{}.START_PLAN id='{}' entered EXPLORING — reason='{}'",
                engineName(process), process.getId(), action.reason());
        return new ActionTurnOutcome(null, /*awaitingUserInput*/ false);
    }

    /**
     * {@code PROPOSE_PLAN} — submit plan + TodoList for user approval.
     * Persists the TodoList atomically, switches mode to PLANNING,
     * emits the plan text as a normal assistant chat message.
     */
    ActionTurnOutcome handleProposePlan(
            EngineAction action, ThinkProcessDocument process, ThinkEngineContext ctx) {
        String plan = action.stringParam(PlanModeActionSchema.PARAM_PLAN);
        String summary = action.stringParam(PlanModeActionSchema.PARAM_SUMMARY);
        List<TodoItem> todos = parseTodos(action.params().get(PlanModeActionSchema.PARAM_TODOS));
        if (plan == null || plan.isBlank()) {
            log.warn("{}.PROPOSE_PLAN id='{}' missing plan — reason='{}'",
                    engineName(process), process.getId(), action.reason());
            return new ActionTurnOutcome(
                    "(internal: PROPOSE_PLAN missing plan text — re-emit with the full plan markdown)",
                    false);
        }
        if (todos.isEmpty()) {
            log.warn("{}.PROPOSE_PLAN id='{}' missing todos — reason='{}'",
                    engineName(process), process.getId(), action.reason());
            return new ActionTurnOutcome(
                    "(internal: PROPOSE_PLAN must include 3–8 todos — re-emit)",
                    false);
        }
        ProcessMode prior = process.getMode();
        thinkProcessService.setTodos(process.getId(), todos);
        thinkProcessService.updateMode(process.getId(), ProcessMode.PLANNING);
        process.setTodos(todos);
        process.setMode(ProcessMode.PLANNING);
        planModeEventEmitter.emitTodosUpdated(process, todos);
        planModeEventEmitter.emitPlanProposed(process, summary, /*planVersion*/ 1);
        if (prior != ProcessMode.PLANNING) {
            planModeEventEmitter.emitModeChanged(process, prior, ProcessMode.PLANNING);
            ctx.historyTagSink().emit(Set.of("MODE:plan"));
        }
        log.info("{}.PROPOSE_PLAN id='{}' summary='{}' todos.size={} reason='{}'",
                engineName(process), process.getId(), summary, todos.size(), action.reason());
        return new ActionTurnOutcome(plan, /*awaitingUserInput*/ true);
    }

    /**
     * {@code START_EXECUTION} — user accepted the plan, begin work.
     * Switches mode to EXECUTING. Tool filter relaxes back to the
     * engine's full pool.
     */
    ActionTurnOutcome handleStartExecution(
            EngineAction action, ThinkProcessDocument process, ThinkEngineContext ctx) {
        String notes = action.stringParam(PlanModeActionSchema.PARAM_NOTES);
        ProcessMode prior = process.getMode();
        thinkProcessService.updateMode(process.getId(), ProcessMode.EXECUTING);
        process.setMode(ProcessMode.EXECUTING);
        planModeEventEmitter.emitModeChanged(process, prior, ProcessMode.EXECUTING);
        ctx.historyTagSink().emit(Set.of("MODE:execute"));
        log.info("{}.START_EXECUTION id='{}' entered EXECUTING — notes='{}' reason='{}'",
                engineName(process), process.getId(), notes, action.reason());
        return new ActionTurnOutcome(null, /*awaitingUserInput*/ false);
    }

    /**
     * {@code TODO_UPDATE} — mark TodoList items as IN_PROGRESS or
     * COMPLETED during execution. No chat message, no mode change.
     */
    ActionTurnOutcome handleTodoUpdate(
            EngineAction action, ThinkProcessDocument process, ThinkEngineContext ctx) {
        Object updatesRaw = action.params().get(PlanModeActionSchema.PARAM_UPDATES);
        Map<String, TodoStatus> updates = parseTodoUpdates(updatesRaw);
        if (updates.isEmpty()) {
            log.warn("{}.TODO_UPDATE id='{}' empty — reason='{}'",
                    engineName(process), process.getId(), action.reason());
            return new ActionTurnOutcome(null, /*awaitingUserInput*/ false);
        }
        boolean ok = thinkProcessService.updateTodoStatuses(process.getId(), updates);
        log.info("{}.TODO_UPDATE id='{}' applied={} count={} reason='{}'",
                engineName(process), process.getId(), ok, updates.size(), action.reason());
        if (ok) {
            // Reload to read the persisted list, then publish the full set —
            // clients replace verbatim (no diff/patch protocol).
            thinkProcessService.findById(process.getId()).ifPresent(refreshed -> {
                process.setTodos(refreshed.getTodos());
                planModeEventEmitter.emitTodosUpdated(refreshed, refreshed.getTodos());
                maybeOfferRecompaction(refreshed);
            });
            // History markers: one tag per transition the LLM commanded.
            // Tags accumulate in the per-turn sink and flush onto the
            // eventual assistant message (often several action loops later,
            // when an ANSWER action emits a chat message).
            Set<String> tags = new LinkedHashSet<>();
            for (Map.Entry<String, TodoStatus> e : updates.entrySet()) {
                switch (e.getValue()) {
                    case IN_PROGRESS -> tags.add("PLAN_STEP_STARTED:" + e.getKey());
                    case COMPLETED -> tags.add("PLAN_STEP_DONE:" + e.getKey());
                    default -> { /* PENDING / CANCELLED — no marker */ }
                }
            }
            if (!tags.isEmpty()) ctx.historyTagSink().emit(tags);
        }
        return new ActionTurnOutcome(null, /*awaitingUserInput*/ false);
    }

    /**
     * Plan-completion hook: when the last {@code TODO_UPDATE} brings
     * every todo into {@code COMPLETED} and there is substantial
     * pre-plan history, posts a {@code RECOMPACTION_OFFER} inbox-item
     * so the user can roll the plan-segment into a memory summary.
     *
     * <p>Pre-plan-context-check counts {@code USER}-role messages with
     * {@code createdAt &lt;} the latest {@code MODE:plan} marker; below
     * {@link #MIN_PRE_PLAN_USER_TURNS} the hook stays quiet. Without a
     * pre-plan thread there is no "topic A" to protect, so the offer
     * would only add noise.
     *
     * <p>Idempotency: posting the same offer twice is cheap (one
     * extra inbox row) but visually redundant. We rely on the user to
     * dismiss duplicates; a stricter dedup (skip if a pending
     * {@code RECOMPACTION_OFFER} for this process already exists)
     * may be added if we see real duplicates in practice.
     *
     * <p>See {@code planning/topic-recompaction.md} §4.
     */
    void maybeOfferRecompaction(ThinkProcessDocument refreshed) {
        List<TodoItem> todos = refreshed.getTodos();
        if (todos == null || todos.isEmpty()) return;
        boolean allCompleted = todos.stream()
                .allMatch(t -> t.getStatus() == TodoStatus.COMPLETED);
        if (!allCompleted) return;

        Optional<Instant> planStartOpt = chatMessageService.findLatestCreatedAtForTag(
                refreshed.getTenantId(), Set.of(refreshed.getId()), "MODE:plan");
        if (planStartOpt.isEmpty()) return;
        Instant planStart = planStartOpt.get();

        List<ChatMessageDocument> prePlan = chatMessageService.findActiveInRange(
                refreshed.getTenantId(), refreshed.getId(),
                /*from*/ null, planStart.minusMillis(1));
        long userTurnsBefore = prePlan.stream()
                .filter(m -> m.getRole() == ChatRole.USER)
                .count();
        if (userTurnsBefore < MIN_PRE_PLAN_USER_TURNS) {
            log.debug("PlanMode recompaction hook skipped for process='{}' — "
                            + "only {} pre-plan USER turn(s) (need {})",
                    refreshed.getId(), userTurnsBefore, MIN_PRE_PLAN_USER_TURNS);
            return;
        }

        String topicLabel = "plan-" + refreshed.getId() + "-" + planStart.toEpochMilli();
        Instant now = Instant.now();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(RecompactionTags.PAYLOAD_RANGE_START_AT, planStart.toString());
        payload.put(RecompactionTags.PAYLOAD_RANGE_END_AT, now.toString());
        payload.put(RecompactionTags.PAYLOAD_TOPIC_LABEL, topicLabel);
        payload.put(RecompactionTags.PAYLOAD_TODO_COUNT, todos.size());

        InboxItemDocument offer = InboxItemDocument.builder()
                .tenantId(refreshed.getTenantId())
                .originatorUserId("plan-mode:" + refreshed.getId())
                .assignedToUserId("")   // unassigned — anyone with access can answer
                .originProcessId(refreshed.getId())
                .originSessionId(refreshed.getSessionId())
                .type(InboxItemType.APPROVAL)
                .criticality(Criticality.NORMAL)
                .tags(new ArrayList<>(List.of(RecompactionTags.TAG_INBOX_OFFER)))
                .title("Plan abgeschlossen — Topic in Memory rollen?")
                .body("Der Plan ist durch. Soll ich die "
                        + todos.size() + " Todo-Turns zu einer Zusammenfassung "
                        + "verdichten? Der rote Faden zum vorherigen Thema "
                        + "bleibt klar — Originale stehen weiter über "
                        + "history_search bereit.")
                .payload(payload)
                .requiresAction(true)
                .build();
        InboxItemDocument saved = inboxItemService.create(offer);
        log.info("PlanMode recompaction offer posted process='{}' inboxItem='{}' "
                        + "topicLabel='{}' todoCount={} userTurnsBefore={}",
                refreshed.getId(), saved.getId(), topicLabel,
                todos.size(), userTurnsBefore);
    }

    // ──────────────────── Helpers ────────────────────

    /**
     * Parses the {@code todos} param of a {@code PROPOSE_PLAN} action
     * into a list of {@link TodoItem}s. Skips entries with missing
     * {@code id} or {@code content}; the LLM's bad input does not
     * crash the turn.
     */
    @SuppressWarnings("unchecked")
    static List<TodoItem> parseTodos(@Nullable Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<TodoItem> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> mapRaw)) continue;
            Map<String, Object> m = (Map<String, Object>) mapRaw;
            String id = strOrNull(m.get("id"));
            String content = strOrNull(m.get("content"));
            String activeForm = strOrNull(m.get("activeForm"));
            if (id == null || id.isBlank() || content == null || content.isBlank()) {
                continue;
            }
            out.add(TodoItem.builder()
                    .id(id)
                    .status(TodoStatus.PENDING)
                    .content(content)
                    .activeForm(activeForm)
                    .build());
        }
        return out;
    }

    /**
     * Parses the {@code updates} param of a {@code TODO_UPDATE} action
     * into an id→status map. Unknown statuses are silently dropped
     * (validator handles re-prompting).
     */
    @SuppressWarnings("unchecked")
    static Map<String, TodoStatus> parseTodoUpdates(@Nullable Object raw) {
        Map<String, TodoStatus> out = new LinkedHashMap<>();
        if (!(raw instanceof List<?> list)) return out;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> mapRaw)) continue;
            Map<String, Object> m = (Map<String, Object>) mapRaw;
            String id = strOrNull(m.get("id"));
            String statusStr = strOrNull(m.get("status"));
            if (id == null || id.isBlank() || statusStr == null) continue;
            try {
                out.put(id, TodoStatus.valueOf(statusStr));
            } catch (IllegalArgumentException ignored) {
                // unknown status — skip, validator catches it via re-prompt
            }
        }
        return out;
    }

    private static @Nullable String strOrNull(@Nullable Object o) {
        return o instanceof String s ? s : null;
    }

    private static @Nullable String paramString(
            ThinkProcessDocument process, String key, @Nullable String fallback) {
        Map<String, Object> params = process.getEngineParams();
        if (params == null) return fallback;
        Object v = params.get(key);
        return v instanceof String s && !s.isBlank() ? s : fallback;
    }

    /** Log-context helper — returns the engine name or {@code ?} on a
     *  malformed process record so log lines stay readable. */
    private static String engineName(ThinkProcessDocument process) {
        String e = process == null ? null : process.getThinkEngine();
        return e == null || e.isBlank() ? "?" : e;
    }
}
