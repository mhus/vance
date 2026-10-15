package de.mhus.vance.brain.wonko;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.question.QuestionAnswerDto;
import de.mhus.vance.api.question.QuestionDto;
import de.mhus.vance.api.question.QuestionKind;
import de.mhus.vance.api.question.QuestionOptionDto;
import de.mhus.vance.api.question.QuestionOutcome;
import de.mhus.vance.api.question.QuestionSetAnswerDto;
import de.mhus.vance.api.question.QuestionSetDto;
import de.mhus.vance.api.question.QuestionWeight;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.api.thinkprocess.TodoItem;
import de.mhus.vance.api.thinkprocess.TodoStatus;
import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.brain.arthur.PlanModeEventEmitter;
import de.mhus.vance.brain.context.LanguageContextResolver;
import de.mhus.vance.brain.discovery.DiscoveryResult;
import de.mhus.vance.brain.discovery.DiscoveryService;
import de.mhus.vance.brain.enginemessage.EngineMessageRouter;
import de.mhus.vance.brain.thinkengine.ChildReply;
import de.mhus.vance.brain.thinkengine.ParentReport;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.document.kind.QuestionsCodec;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.thinkprocess.PendingMessageType;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Wonko — checks a subject for sense, contradictions and gaps and clarifies
 * it with the user (plan {@code planning/wonko-engine.md}).
 *
 * <p>The working state is a <b>check queue</b> of small typed items (§4a):
 * without {@code references} one {@code CHECK} item reviews the whole subject;
 * with {@code references} the subject is decomposed into claims, each claim
 * locates its reference passages, is compared against them (compares run in
 * parallel), and every reference document hit gets a coverage check. Every
 * handler returns a defined result with an escape outcome and a reason
 * ({@link WonkoItemHandlers}). The run ends when no item and no question is
 * open — not when a model stops calling tools.
 *
 * <p>Findings collect in a question pool; a {@link QuestionSetDto} goes out
 * (a {@code questions} fence in the chat) when the pool is full or the queue has run
 * dry, and the queue keeps working while the user answers. Answers come back
 * typed ({@link SteerMessage.UserChatInput#questionAnswers()}) or as free text
 * (interpret recipe), land in the register in project memory, and queue a
 * recheck of exactly the claims they touch.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WonkoEngine implements ThinkEngine {

    public static final String NAME = "wonko";
    public static final String VERSION = "0.2.0";
    public static final String STATE_KEY = "wonkoState";

    static final String PARAM_SUBJECT = "subject";
    static final String PARAM_REFERENCES = "references";
    static final String PARAM_MANDATE = "mandate";
    static final String PARAM_CHECKLIST = "checklist";

    private static final String DEFAULT_GOAL = "Check the subject for sense, contradictions and gaps.";

    private static final String ASK_FOR_SUBJECT =
            "What should I review? Open the document in Cortex, or name one or " + "more documents by path or id.";

    private static final Pattern DOCUMENT_ID = Pattern.compile("[0-9a-f]{24}");

    private static final Pattern PATH_LIKE = Pattern.compile("[\\w.-]+(/[\\w.-]+)+|[\\w-]+\\.[a-zA-Z0-9]{1,5}");

    /** Prefix of question keys that carry a mandate proposal instead of a topic. */
    static final String PROPOSAL_KEY_PREFIX = "mandate-proposal/";

    /** Question key of the budget checkpoint. */
    static final String CHECKPOINT_KEY = "budget-checkpoint";

    /** Key of the question that offers a follow-up at the end of a run. */
    static final String FOLLOWUP_KEY = "followup";

    static final String CHECKPOINT_CONTINUE = "Continue";
    static final String CHECKPOINT_STOP = "Stop";

    private static final String TODO_ASK = "wonko-ask";

    private static final String METRIC_ITEMS = "vance.wonko.items";
    private static final String METRIC_OUTCOMES = "vance.wonko.outcomes";

    private final ThinkProcessService thinkProcessService;
    private final ObjectMapper objectMapper;
    private final LightLlmService lightLlmService;
    private final WonkoRegisterService registerService;
    private final WonkoItemHandlers handlers;
    private final PlanModeEventEmitter planModeEventEmitter;
    private final ProcessEventEmitter eventEmitter;
    private final SessionService sessionService;
    private final LanguageContextResolver languageContextResolver;
    private final MetricService metricService;
    private final WonkoSkills wonkoSkills;
    private final DiscoveryService discoveryService;
    private final EngineMessageRouter messageRouter;

    // ──────────────────── Metadata ────────────────────

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String title() {
        return "Wonko (Review Loop)";
    }

    @Override
    public String description() {
        return "Checks a subject for sense, contradictions and gaps — also against reference documents — and "
                + "clarifies it with the user.";
    }

    @Override
    public String version() {
        return VERSION;
    }

    /** The queue waits for the user — an orchestrator must not block on the lane. */
    @Override
    public boolean asyncSteer() {
        return true;
    }

    /** Wonko reads subject and references through the permission-checked document tools. */
    @Override
    public Set<String> allowedTools() {
        return Set.of("doc_read", "doc_list_in_folder", "process_spawn");
    }

    // ──────────────────── Lifecycle ────────────────────

    @Override
    public void start(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.info(
                "Wonko.start tenant='{}' session='{}' id='{}'",
                process.getTenantId(),
                process.getSessionId(),
                process.getId());
        WonkoState state = loadState(process);
        if (state.getSubjectRefs().isEmpty()) {
            List<String> refs = subjectRefs(params(process).get(PARAM_SUBJECT));
            if (!refs.isEmpty()) {
                setSubject(state, refs);
                seedQueue(process, ctx, state);
                persistState(process, state);
            }
        }
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
        if (state.getSubjectRefs().isEmpty()) {
            // A chat without a set subject: the next message brings it — the
            // document bound in Cortex, or ids/paths named in the text.
            if (!state.isSubjectRequested()) {
                state.setSubjectRequested(true);
                persistState(process, state);
                appendChat(process, ctx, ChatRole.ASSISTANT, ASK_FOR_SUBJECT, null);
            }
            return;
        }
        // The work runs on the lane, not on the spawner's thread.
        eventEmitter.scheduleTurn(process.getId());
    }

    @Override
    public void resume(ThinkProcessDocument process, ThinkEngineContext ctx) {
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
        eventEmitter.scheduleTurn(process.getId());
    }

    @Override
    public void suspend(ThinkProcessDocument process, ThinkEngineContext ctx) {
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.SUSPENDED);
    }

    @Override
    public void steer(ThinkProcessDocument process, ThinkEngineContext ctx, SteerMessage message) {
        eventEmitter.scheduleTurn(process.getId());
    }

    /**
     * Drains the inbox, folds messages into state and register, works the
     * queue — and goes again while messages arrived during the work.
     */
    @Override
    public void runTurn(ThinkProcessDocument process, ThinkEngineContext ctx) {
        do {
            WonkoState state = loadState(process);
            boolean stop = ingest(process, ctx, state, ctx.drainPending());
            persistState(process, state);
            if (stop) {
                finish(process, ctx, state, loadRegister(process, state), "Stopped on the user's request.", "stopped");
            } else if (!state.getSubjectRefs().isEmpty() && !state.isFinished()) {
                work(process, ctx, state);
            }
        } while (ctx.hasPending());
    }

    @Override
    public void stop(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.info("Wonko.stop id='{}'", process.getId());
        thinkProcessService.closeProcess(process.getId(), CloseReason.STOPPED);
    }

    @Override
    public ParentReport summarizeForParent(ThinkProcessDocument process, ProcessEventType eventType) {
        WonkoState state = loadState(process);
        if (state.getFinalReport() != null) {
            return ParentReport.of(state.getFinalReport());
        }
        QuestionSetDto pending = state.getPendingSet();
        if (pending != null) {
            return ParentReport.of(WonkoPrompts.questionMessage(pending, List.of()));
        }
        return ParentReport.of(
                "Wonko process " + process.getId() + " " + eventType.name().toLowerCase(Locale.ROOT));
    }

    // ──────────────────── Ingest ────────────────────

    /** @return {@code true} when the user asked to end the review */
    private boolean ingest(
            ThinkProcessDocument process, ThinkEngineContext ctx, WonkoState state, List<SteerMessage> drained) {
        boolean stop = false;
        WonkoRegister register = null;
        for (SteerMessage msg : drained) {
            if (msg instanceof SteerMessage.Reply || msg instanceof SteerMessage.ProcessEvent) {
                if (register == null && !state.getSubjectRefs().isEmpty()) {
                    register = loadRegister(process, state);
                }
                onWorkerMessage(process, ctx, state, msg);
                continue;
            }
            if (!(msg instanceof SteerMessage.UserChatInput input)) {
                log.debug(
                        "Wonko id='{}' ignoring {}",
                        process.getId(),
                        msg.getClass().getSimpleName());
                continue;
            }
            QuestionSetDto open = state.getPendingSet();
            if (open == null || state.getSubjectRefs().isEmpty()) {
                appendChat(process, ctx, ChatRole.USER, input.content(), null);
            }
            if (state.getSubjectRefs().isEmpty()) {
                if (adoptSubject(process, ctx, state, input)) {
                    seedQueue(process, ctx, state);
                    // "Check X and work it in": the part for after the review waits.
                    rememberFollowUp(process, ctx, state, loadRegister(process, state), input.content());
                }
                continue;
            }
            if (register == null) {
                register = loadRegister(process, state);
            }
            if (open != null) {
                stop |= applyAnswer(process, register, state, input);
                // The user message is recorded after the answer is applied, so
                // it can say whether it answered the set.
                appendChat(
                        process,
                        ctx,
                        ChatRole.USER,
                        input.content(),
                        state.getPendingSet() == null
                                ? Map.of(ChatMessageDocument.META_QUESTIONS_ANSWERED, open.getId())
                                : null);
                if (state.getPendingSet() != null) {
                    // Nothing of it answered the open questions: a question to
                    // Wonko, a task, or a reply it cannot read — the set stays open.
                    WonkoPrompts.RouteResult route = route(process, ctx, state, register, input.content(), null);
                    if (route != null && route.intent() == WonkoPrompts.RouteIntent.WORK) {
                        startWork(process, ctx, state, register, route, input.content());
                    } else if (route != null && route.intent() == WonkoPrompts.RouteIntent.WORKER) {
                        answerWorker(process, ctx, state, register, route, input.content());
                    } else {
                        appendChat(
                                process,
                                ctx,
                                ChatRole.ASSISTANT,
                                route != null && route.answer() != null
                                        ? route.answer()
                                        : "I could not map that reply onto the open questions — they are still open.",
                                null);
                    }
                }
                if (!stop && state.getPendingSet() == null && state.isRecheckDeferred()) {
                    // The set a worker's change waited for is answered — now the recheck.
                    startRecheck(process, ctx, state);
                }
                // A confirmed follow-up comes in like a new message.
                while (!state.getFollowUpInputs().isEmpty()) {
                    routeFreeMessage(
                            process,
                            ctx,
                            state,
                            register,
                            state.getFollowUpInputs().remove(0),
                            null);
                }
                // Free text instead of a confirmation: a free message, the offer closed.
                while (!state.getFollowUpReplies().isEmpty()) {
                    WonkoState.FollowUpReply reply = state.getFollowUpReplies().remove(0);
                    routeFreeMessage(process, ctx, state, register, reply.getText(), reply.getOffer());
                }
            } else if (!input.content().isBlank()) {
                routeFreeMessage(process, ctx, state, register, input.content(), null);
            }
        }
        if (register != null) {
            registerService.save(process.getTenantId(), process.getProjectId(), register);
        }
        return stop;
    }

    /**
     * A message outside a question set (plan §8.3): the route judge decides
     * whether it asks for a new run, brings information for the claims, or
     * just wants an answer — a question about the review must not restart it.
     * While a worker waits, the judge may also hand the message to it. Without
     * a verdict the message goes to a waiting worker (its reply was the last
     * thing said), otherwise it counts as a statement.
     *
     * @param closedOffer a follow-up offer this message answered instead of
     *                    confirming it, {@code null} for any other message
     */
    private void routeFreeMessage(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            WonkoState state,
            WonkoRegister register,
            String text,
            @Nullable String closedOffer) {
        WonkoPrompts.RouteResult route = route(process, ctx, state, register, text, closedOffer);
        WonkoPrompts.RouteIntent intent = route != null
                ? route.intent()
                : workerWaits(state) ? WonkoPrompts.RouteIntent.WORKER : WonkoPrompts.RouteIntent.STATEMENT;
        log.info(
                "Wonko id='{}' routed a message as {}{}",
                process.getId(),
                intent,
                route == null ? " (route failed)" : " — " + route.reason());
        if (route != null
                && route.followUp() != null
                && intent != WonkoPrompts.RouteIntent.WORK
                && intent != WonkoPrompts.RouteIntent.WORKER) {
            state.getFollowUps().add(route.followUp());
        }
        switch (intent) {
            case RECHECK -> startRecheck(process, ctx, state);
            case WORK -> startWork(process, ctx, state, register, route, text);
            case WORKER -> answerWorker(process, ctx, state, register, route, text);
            case STATEMENT -> {
                state.getUserStatements().add(text);
                state.setFinished(false);
                state.setFinalReport(null);
                requeueAfterStatement(state);
            }
            case QUESTION, UNCLEAR ->
                appendChat(
                        process,
                        ctx,
                        ChatRole.ASSISTANT,
                        route != null && route.answer() != null
                                ? route.answer()
                                : "I did not understand that — should I check the subject again?",
                        null);
        }
    }

    /**
     * The route judge; {@code null} when the call fails. An answer that needs
     * know-how the judge lacks (how to link a document, …) names a
     * {@code lookup}: the how-do-I discovery finds the manual, and the judge
     * answers once more with it — Wonko's {@code how_do_i}, without a tool
     * loop.
     */
    private WonkoPrompts.@Nullable RouteResult route(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            WonkoState state,
            WonkoRegister register,
            String text,
            @Nullable String closedOffer) {
        String report = state.getFinalReport() != null
                ? state.getFinalReport()
                : WonkoReportRenderer.render(register, "", state);
        // The subject as it is now — "summarise the document" needs the text, not the findings.
        WonkoTurn reader = new WonkoTurn(
                process, ctx, state, register, new WonkoParams(params(process)), "", userIdFor(process), "");
        List<WonkoPrompts.SubjectDoc> subject = new ArrayList<>();
        for (String ref : state.getSubjectRefs()) {
            String body = reader.doc(ref);
            if (body != null) {
                subject.add(new WonkoPrompts.SubjectDoc(ref, body));
            }
        }
        boolean workerWaits = workerWaits(state);
        String workerReply = workerWaits ? Objects.requireNonNullElse(state.getWorkerLastReply(), "") : null;
        String prompt = WonkoPrompts.routePrompt(
                state.getSubjectRefs(),
                subject,
                report,
                state.getPendingSet(),
                recentTurns(process, ctx, text),
                text,
                workerReply,
                closedOffer);
        try {
            WonkoPrompts.RouteResult first = callRoute(process, prompt, workerWaits);
            if (first.lookup() == null
                    || first.intent() == WonkoPrompts.RouteIntent.RECHECK
                    || first.intent() == WonkoPrompts.RouteIntent.STATEMENT
                    || first.intent() == WonkoPrompts.RouteIntent.WORKER) {
                return first;
            }
            String manual = lookup(process, first.lookup());
            return manual == null ? first : callRoute(process, WonkoPrompts.withManual(prompt, manual), workerWaits);
        } catch (RuntimeException e) {
            log.warn("Wonko id='{}' could not route a message: {}", process.getId(), e.toString());
            return null;
        }
    }

    /**
     * The conversation so far, oldest first, without the message being routed —
     * a "yes" only means something next to the question it answers.
     */
    private List<WonkoPrompts.Turn> recentTurns(ThinkProcessDocument process, ThinkEngineContext ctx, String current) {
        List<WonkoPrompts.Turn> turns = new ArrayList<>();
        try {
            for (ChatMessageDocument m : ctx.chatMessageService()
                    .activeHistory(process.getTenantId(), process.getSessionId(), process.getId())) {
                if ((m.getRole() == ChatRole.USER || m.getRole() == ChatRole.ASSISTANT)
                        && m.getContent() != null
                        && !m.getContent().isBlank()) {
                    turns.add(new WonkoPrompts.Turn(m.getRole().name().toLowerCase(Locale.ROOT), m.getContent()));
                }
            }
        } catch (RuntimeException e) {
            log.warn("Wonko id='{}' could not read the chat history: {}", process.getId(), e.toString());
        }
        if (!turns.isEmpty() && turns.get(turns.size() - 1).text().equals(current)) {
            turns.remove(turns.size() - 1);
        }
        return turns;
    }

    /** The follow-up part of a message, kept for the end of the run. */
    private void rememberFollowUp(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            WonkoState state,
            WonkoRegister register,
            String text) {
        WonkoPrompts.RouteResult route = route(process, ctx, state, register, text, null);
        if (route != null && route.followUp() != null) {
            state.getFollowUps().add(route.followUp());
            log.info("Wonko id='{}' follow-up kept for after the review: {}", process.getId(), route.followUp());
        }
    }

    // ──────────────────── Work (a worker does the task) ────────────────────

    /**
     * The router's {@code work}: a task that needs tools goes to a worker —
     * a plain tool loop with skills, manuals and how-do-I — together with the
     * review's result. Its reply comes back as an event (see
     * {@link #onWorkerMessage}).
     */
    private void startWork(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            WonkoState state,
            WonkoRegister register,
            WonkoPrompts.@Nullable RouteResult route,
            String text) {
        String task = route != null && route.task() != null ? route.task() : text;
        String report = state.getFinalReport() != null
                ? state.getFinalReport()
                : WonkoReportRenderer.render(register, "", state);
        if (state.getWorkerName() != null) {
            // The worker of an earlier task waits for more — it keeps its context.
            state.setSubjectFingerprint(subjectFingerprint(process, ctx, state, register));
            forwardToWorker(process, ctx, state, WonkoPrompts.workTask(task, state.getSubjectRefs(), report));
            if (state.getWorkerName() != null) {
                log.info("Wonko id='{}' steered worker '{}' with: {}", process.getId(), state.getWorkerName(), task);
                appendChat(
                        process,
                        ctx,
                        ChatRole.ASSISTANT,
                        route != null && route.answer() != null ? route.answer() : "On it: " + task,
                        null);
                return;
            }
        }
        String name = "wonko-work-" + UUID.randomUUID().toString().substring(0, 6);
        try {
            Map<String, Object> spawn = new LinkedHashMap<>();
            spawn.put("name", name);
            spawn.put("task", WonkoPrompts.workTask(task, state.getSubjectRefs(), report));
            spawn.put("recipe", new WonkoParams(params(process)).workRecipe());
            ctx.tools().invokeInternal("process_spawn", spawn);
        } catch (RuntimeException e) {
            log.warn("Wonko id='{}' could not start the work: {}", process.getId(), e.toString());
            appendChat(process, ctx, ChatRole.ASSISTANT, "I could not start that task: " + e.getMessage(), null);
            return;
        }
        state.setWorkerName(name);
        state.setWorkerId(thinkProcessService
                .findByName(process.getTenantId(), process.getSessionId(), name)
                .map(ThinkProcessDocument::getId)
                .orElse(null));
        state.setWorkerWaiting(false);
        state.setWorkerLastReply(null);
        state.setWorkerReplied(false);
        state.setSubjectFingerprint(subjectFingerprint(process, ctx, state, register));
        log.info("Wonko id='{}' started worker '{}' for: {}", process.getId(), name, task);
        appendChat(
                process,
                ctx,
                ChatRole.ASSISTANT,
                route != null && route.answer() != null ? route.answer() : "On it: " + task,
                null);
    }

    /**
     * A message of the worker: its reply goes to the chat; when it ends, a
     * changed subject is checked again.
     *
     * <p>A worker asks the user the way it answers: a tool loop ends its turn
     * with a reply and stays IDLE (natural stop) — no lifecycle event follows,
     * {@code BLOCKED} included ({@code ParentNotificationListener} maps it to
     * nothing on purpose). So the reply itself is the signal: after it the
     * worker waits for input, and the route judge reads the user's next
     * message against that reply ({@code worker} intent) — "A" to "A or B?"
     * reaches the worker unchanged instead of restarting the review.
     */
    private void onWorkerMessage(
            ThinkProcessDocument process, ThinkEngineContext ctx, WonkoState state, SteerMessage msg) {
        String workerId = state.getWorkerId();
        if (workerId == null) {
            return;
        }
        if (msg instanceof SteerMessage.Reply reply && workerId.equals(reply.sourceProcessId())) {
            if (!reply.content().isBlank()) {
                appendChat(process, ctx, ChatRole.ASSISTANT, reply.content(), null);
                state.setWorkerReplied(true);
                state.setWorkerLastReply(reply.content());
            }
            // A reply closes this step of the work — the worker itself waits
            // for more (a tool loop ends its turn, not its life).
            state.setWorkerWaiting(true);
            recheckIfSubjectChanged(process, ctx, state);
            return;
        }
        if (!(msg instanceof SteerMessage.ProcessEvent event) || !workerId.equals(event.sourceProcessId())) {
            return;
        }
        String body = ChildReply.unwrap(event.humanSummary());
        switch (event.type()) {
            case DONE, FAILED, STOPPED -> {
                if (!state.isWorkerReplied() && body != null && !body.isBlank()) {
                    appendChat(process, ctx, ChatRole.ASSISTANT, body, null);
                }
                log.info("Wonko id='{}' worker '{}' ended ({})", process.getId(), state.getWorkerName(), event.type());
                recheckIfSubjectChanged(process, ctx, state);
                state.setWorkerName(null);
                state.setWorkerId(null);
                state.setWorkerWaiting(false);
                state.setWorkerLastReply(null);
                state.setWorkerReplied(false);
                state.setSubjectFingerprint(null);
            }
            default -> {
                // progress and other lifecycle markers say nothing to the user
            }
        }
    }

    /**
     * After a step of the work: a changed subject is reviewed again — once per
     * step, the fingerprint moves on so the same change does not trigger twice.
     * While a question set is open the recheck waits for its answers: a fresh
     * run now would drop the set silently, and the answers that come later
     * would land nowhere. The user is told either way.
     */
    private void recheckIfSubjectChanged(ThinkProcessDocument process, ThinkEngineContext ctx, WonkoState state) {
        String before = state.getSubjectFingerprint();
        if (before == null) {
            return;
        }
        String after = subjectFingerprint(process, ctx, state, loadRegister(process, state));
        state.setSubjectFingerprint(after);
        if (before.equals(after)) {
            return;
        }
        if (state.getPendingSet() != null) {
            state.setRecheckDeferred(true);
            appendChat(
                    process,
                    ctx,
                    ChatRole.ASSISTANT,
                    "The subject changed — I will check it again once the open questions are answered.",
                    null);
            return;
        }
        appendChat(process, ctx, ChatRole.ASSISTANT, "The subject changed — checking it again.", null);
        startRecheck(process, ctx, state);
    }

    /** A fresh run on the same subject; the register keeps what was clarified. */
    private void startRecheck(ThinkProcessDocument process, ThinkEngineContext ctx, WonkoState state) {
        state.resetRun();
        seedQueue(process, ctx, state);
    }

    /** Whether a worker waits for its next input — the judge may then hand it a message. */
    private static boolean workerWaits(WonkoState state) {
        return state.isWorkerWaiting() && state.getWorkerId() != null;
    }

    /**
     * The router's {@code worker}: the message answers the waiting worker or
     * continues its task — it goes to the worker as it is, not wrapped as a
     * new task. Without a waiting worker it is a task of its own.
     */
    private void answerWorker(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            WonkoState state,
            WonkoRegister register,
            WonkoPrompts.@Nullable RouteResult route,
            String text) {
        if (!workerWaits(state)) {
            startWork(process, ctx, state, register, route, text);
            return;
        }
        // The worker's change, if any, is measured from here.
        state.setSubjectFingerprint(subjectFingerprint(process, ctx, state, register));
        forwardToWorker(process, ctx, state, text);
        log.info("Wonko id='{}' handed a message to worker '{}'", process.getId(), state.getWorkerName());
    }

    /**
     * A message for the worker — into its inbox through the message router,
     * the way Arthur forwards to a delegated worker; its lane wakes itself and
     * the answer comes back as an event.
     */
    private void forwardToWorker(ThinkProcessDocument process, ThinkEngineContext ctx, WonkoState state, String text) {
        String workerId = state.getWorkerId();
        boolean delivered = workerId != null
                && messageRouter.dispatch(
                        process.getId(),
                        workerId,
                        PendingMessageDocument.builder()
                                .type(PendingMessageType.USER_CHAT_INPUT)
                                .at(Instant.now())
                                .fromUser("wonko:" + process.getId())
                                .content(text)
                                .build());
        if (delivered) {
            state.setWorkerWaiting(false);
            state.setWorkerLastReply(null);
            state.setWorkerReplied(false);
            return;
        }
        log.warn("Wonko id='{}' could not reach worker '{}'", process.getId(), state.getWorkerName());
        appendChat(process, ctx, ChatRole.ASSISTANT, "The task is not reachable any more.", null);
        state.setWorkerName(null);
        state.setWorkerId(null);
        state.setWorkerWaiting(false);
        state.setWorkerLastReply(null);
    }

    /** A fingerprint of the subject's current text — tells whether a worker changed it. */
    private String subjectFingerprint(
            ThinkProcessDocument process, ThinkEngineContext ctx, WonkoState state, WonkoRegister register) {
        WonkoTurn reader = new WonkoTurn(
                process, ctx, state, register, new WonkoParams(params(process)), "", userIdFor(process), "");
        StringBuilder sb = new StringBuilder();
        for (String ref : state.getSubjectRefs()) {
            String body = reader.doc(ref);
            sb.append(ref).append('\0').append(body == null ? "" : body).append('\0');
        }
        return Integer.toHexString(sb.toString().hashCode()) + ":" + sb.length();
    }

    private WonkoPrompts.RouteResult callRoute(ThinkProcessDocument process, String prompt, boolean workerWaits) {
        List<String> skills = skills(process, new WonkoParams(params(process))).names();
        return WonkoPrompts.parseRoute(lightLlmService.callForJson(LightLlmRequest.builder()
                .recipeName(new WonkoParams(params(process)).routeRecipe())
                .userPrompt(WonkoItemHandlers.withLanguage(languageContextResolver.formatBlock(process), prompt))
                .schema(WonkoPrompts.routeSchema(workerWaits))
                .tenantId(process.getTenantId())
                .projectId(process.getProjectId())
                .processId(process.getId())
                .userId(userIdFor(process))
                .skills(skills.isEmpty() ? null : skills)
                .build()));
    }

    /** The manual the how-do-I discovery finds for an intent; {@code null} without a confident match. */
    private @Nullable String lookup(ThinkProcessDocument process, String intent) {
        try {
            DiscoveryResult result =
                    discoveryService.discover(intent, process.getTenantId(), process.getProjectId(), process.getId());
            DiscoveryResult.Match loaded = result.getLoaded();
            if (loaded == null
                    || loaded.getContent() == null
                    || loaded.getContent().isBlank()) {
                log.info("Wonko id='{}' lookup '{}' found no manual", process.getId(), intent);
                return null;
            }
            log.info("Wonko id='{}' lookup '{}' → manual '{}'", process.getId(), intent, loaded.getName());
            return "## Manual: " + loaded.getName() + "\n" + loaded.getContent();
        } catch (RuntimeException e) {
            log.warn("Wonko id='{}' lookup '{}' failed: {}", process.getId(), intent, e.toString());
            return null;
        }
    }

    /** First items of a run: decompose against references, or the whole-subject check. */
    private void seedQueue(ThinkProcessDocument process, ThinkEngineContext ctx, WonkoState state) {
        state.setReferencePaths(
                resolveReferences(ctx, subjectRefs(params(process).get(PARAM_REFERENCES))));
        state.setCallBudget(new WonkoParams(params(process)).maxCalls());
        WonkoItemHandlers.enqueue(
                state, state.getReferencePaths().isEmpty() ? WonkoItemType.CHECK : WonkoItemType.DECOMPOSE, 0, null);
        state.getPendingFindings().addAll(WonkoQuestionPlanner.carryOver(loadRegister(process, state)));
        // Once per run: a selected skill that cannot take effect is named, not dropped silently.
        for (String skipped : skills(process, new WonkoParams(params(process))).skipped()) {
            state.getDecisions().add(new WonkoDecision("skills", "skipped", skipped));
        }
    }

    private WonkoSkills.Selection skills(ThinkProcessDocument process, WonkoParams params) {
        return wonkoSkills.select(process, userIdFor(process), params.skills());
    }

    private static void requeueAfterStatement(WonkoState state) {
        if (state.getReferencePaths().isEmpty()) {
            if (state.getQueue().stream().noneMatch(i -> i.getType() == WonkoItemType.CHECK)) {
                WonkoItemHandlers.enqueue(state, WonkoItemType.CHECK, 0, null);
            }
            return;
        }
        state.getClaims().forEach(c -> WonkoItemHandlers.enqueueRecheck(state, c.getId()));
    }

    /**
     * References: a path ending in {@code /} is a folder (all documents below
     * it), anything else a document path.
     */
    private List<String> resolveReferences(ThinkEngineContext ctx, List<String> refs) {
        List<String> out = new ArrayList<>();
        for (String ref : refs) {
            if (!ref.endsWith("/")) {
                if (!out.contains(ref)) {
                    out.add(ref);
                }
                continue;
            }
            try {
                Object docs = ctx.tools()
                        .invoke("doc_list_in_folder", Map.of("folder", ref, "recursive", true))
                        .get("documents");
                if (docs instanceof List<?> list) {
                    for (Object o : list) {
                        if (o instanceof Map<?, ?> m && m.get("path") instanceof String p && !out.contains(p)) {
                            out.add(p);
                        }
                    }
                }
            } catch (RuntimeException e) {
                log.warn("Wonko could not list reference folder '{}': {}", ref, e.toString());
            }
        }
        return out;
    }

    // ──────────────────── Subject from the chat ────────────────────

    /**
     * Takes the subject from a chat message when none was set: the document
     * bound in Cortex for this turn first, otherwise document ids or paths
     * named in the text — each checked through {@code doc_read}. The message
     * text becomes the goal of the review.
     *
     * @return {@code true} when a subject was found
     */
    private boolean adoptSubject(
            ThinkProcessDocument process, ThinkEngineContext ctx, WonkoState state, SteerMessage.UserChatInput input) {
        List<String> found = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        if (input.boundDocumentId() != null && !input.boundDocumentId().isBlank()) {
            resolveDocument(ctx, Map.of("id", input.boundDocumentId()), input.boundDocumentId(), found, missing);
        }
        if (found.isEmpty()) {
            for (String ref : referenceTokens(input.content())) {
                Map<String, Object> query =
                        DOCUMENT_ID.matcher(ref).matches() ? Map.of("id", ref) : Map.of("path", ref);
                resolveDocument(ctx, query, ref, found, missing);
            }
        }
        if (found.isEmpty()) {
            String tried = missing.isEmpty() ? "" : " (not found: " + String.join(", ", missing) + ")";
            appendChat(
                    process,
                    ctx,
                    ChatRole.ASSISTANT,
                    "I have no document to review yet" + tried + ". " + ASK_FOR_SUBJECT,
                    null);
            return false;
        }
        setSubject(state, found);
        if (!input.content().isBlank()) {
            state.setGoal(input.content());
        }
        log.info("Wonko id='{}' subject from chat: {}", process.getId(), found);
        return true;
    }

    private static void resolveDocument(
            ThinkEngineContext ctx, Map<String, Object> query, String ref, List<String> found, List<String> missing) {
        try {
            Object path = ctx.tools().invoke("doc_read", query).get("path");
            if (path instanceof String p && !p.isBlank() && !found.contains(p)) {
                found.add(p);
            }
        } catch (RuntimeException e) {
            missing.add(ref);
        }
    }

    /**
     * Words of a message that look like a document reference: a document id
     * (24 hex chars) or a path (contains {@code /} or ends in a file
     * extension). Quotes, backticks and trailing punctuation are stripped.
     */
    static List<String> referenceTokens(String text) {
        List<String> out = new ArrayList<>();
        for (String raw : text.split("[\\s,;]+")) {
            String token = raw.replaceAll("^[`'\"(<\\[]+|[`'\")>\\].:!?]+$", "");
            if (token.isEmpty() || out.contains(token)) {
                continue;
            }
            if (DOCUMENT_ID.matcher(token).matches() || PATH_LIKE.matcher(token).matches()) {
                out.add(token);
            }
        }
        return out;
    }

    private static void setSubject(WonkoState state, List<String> refs) {
        state.setSubjectRefs(new ArrayList<>(refs));
        state.setSubjectKey(WonkoRegisterService.subjectKey(refs));
    }

    // ──────────────────── Answers ────────────────────

    /**
     * Folds one reply to the pending set into the register. Typed answers are
     * applied as they are; free text goes through the interpret recipe first.
     * Answered topics queue a recheck of the claim they belong to.
     *
     * @return {@code true} when the user asked to end the review
     */
    private boolean applyAnswer(
            ThinkProcessDocument process, WonkoRegister register, WonkoState state, SteerMessage.UserChatInput input) {
        QuestionSetDto set = state.getPendingSet();
        if (set == null) {
            return false;
        }
        QuestionSetAnswerDto typed = input.questionAnswers();
        List<QuestionAnswerDto> answers;
        boolean stop = false;
        if (typed != null && set.getId().equals(typed.getSetId())) {
            answers = typed.getAnswers();
        } else {
            WonkoPrompts.InterpretResult interpreted;
            try {
                interpreted = WonkoPrompts.parseInterpret(lightLlmService.callForJson(LightLlmRequest.builder()
                        .recipeName(new WonkoParams(params(process)).interpretRecipe())
                        .userPrompt(WonkoPrompts.interpretPrompt(set, input.content()))
                        .schema(WonkoPrompts.interpretSchema())
                        .tenantId(process.getTenantId())
                        .projectId(process.getProjectId())
                        .processId(process.getId())
                        .userId(userIdFor(process))
                        .build()));
            } catch (RuntimeException e) {
                // The questions stay open — nothing of the reply is lost but its
                // mapping; the statement still reaches the next check.
                log.warn("Wonko id='{}' could not interpret a free-text reply: {}", process.getId(), e.toString());
                state.getUserStatements().add(input.content());
                return false;
            }
            if (interpreted.answers().isEmpty()
                    && !interpreted.stop()
                    && interpreted.mandateChanges().isEmpty()) {
                // Not an answer to the set — it stays open, the caller routes the message.
                return false;
            }
            for (WonkoPrompts.UserMandateChange change : interpreted.mandateChanges()) {
                WonkoMandateRules.userSet(
                        register, change.category(), change.level(), change.locked(), state.getRound());
            }
            state.getDecisions().addAll(interpreted.unclear());
            state.getUserStatements().add(input.content());
            answers = interpreted.answers();
            stop = interpreted.stop();
        }
        takeFollowUpAnswer(state, set, answers);
        if (state.isCheckpointAsked()) {
            stop |= applyCheckpoint(process, state, answers);
        }
        for (QuestionAnswerDto a : answers) {
            // Only an explicit "later" postpones — a question the reply simply
            // left out stays askable.
            if (a.getOutcome() == QuestionOutcome.LATER
                    && !state.getPostponedKeys().contains(a.getKey())) {
                state.getPostponedKeys().add(a.getKey());
            }
        }
        WonkoState.RoundStat answered =
                WonkoAnswerApplier.apply(register, set, answers, state.getPendingProposals(), state.getRound());
        mergeAnswerStat(state, answered);
        queueRechecks(state, register, set);
        state.setPendingSet(null);
        state.getPendingProposals().clear();
        state.setCheckpointAsked(false);
        return stop;
    }

    /**
     * The answer to an offered follow-up (a {@code confirm} question).
     *
     * <ul>
     *   <li>Confirmed — {@code ANSWERED} without a value (the confirm button)
     *       or with the proposal itself (how the interpret recipe writes an
     *       agreement): the follow-up enters the intake like a new message.</li>
     *   <li>Free text — {@code ANSWERED} with anything else: not a consent.
     *       "No, not now" must not run the offer. The offer is closed and the
     *       text enters the intake as a free message with the closed offer in
     *       view; only a judge that reads it as agreement turns it into work.</li>
     *   <li>Any other outcome, or no answer for it: the follow-up is dropped.</li>
     * </ul>
     */
    static void takeFollowUpAnswer(WonkoState state, QuestionSetDto set, List<QuestionAnswerDto> answers) {
        boolean offered = set.getQuestions().stream().anyMatch(q -> FOLLOWUP_KEY.equals(q.getKey()));
        if (!offered || state.getFollowUps().isEmpty()) {
            return;
        }
        String followUp = state.getFollowUps().remove(0);
        QuestionAnswerDto answer = answers.stream()
                .filter(a -> FOLLOWUP_KEY.equals(a.getKey()) && a.getOutcome() == QuestionOutcome.ANSWERED)
                .findFirst()
                .orElse(null);
        if (answer == null) {
            return;
        }
        String value = answer.getValue() == null ? "" : answer.getValue().strip();
        if (value.isEmpty() || value.equalsIgnoreCase(followUp.strip())) {
            state.getFollowUpInputs().add(followUp);
        } else {
            state.getFollowUpReplies().add(new WonkoState.FollowUpReply(followUp, value));
        }
    }

    /**
     * Offers the first follow-up as a question at the end of a run — nothing
     * runs unasked; open blockers are named as what will not be worked in.
     */
    private void offerFollowUp(
            ThinkProcessDocument process, ThinkEngineContext ctx, WonkoState state, WonkoRegister register) {
        List<String> blockers = register.getTopics().stream()
                .filter(t -> t.getWeight() == QuestionWeight.BLOCKER
                        && (t.getStatus() == WonkoTopicStatus.OPEN
                                || t.getStatus() == WonkoTopicStatus.ASKED
                                || t.getStatus() == WonkoTopicStatus.LATER))
                .map(t -> t.getTitle().isBlank() ? t.getKey() : t.getTitle())
                .toList();
        QuestionSetDto set = QuestionSetDto.builder()
                .id(UUID.randomUUID().toString())
                .round(state.getRound() + 1)
                .questions(new ArrayList<>(List.of(WonkoPrompts.followUpQuestion(
                        FOLLOWUP_KEY, state.getFollowUps().get(0), blockers))))
                .build();
        state.setPendingSet(set);
        persistState(process, state);
        appendChat(process, ctx, ChatRole.ASSISTANT, QuestionsCodec.toFence(set), null);
    }

    /** The budget checkpoint: "continue" grants another budget, anything else ends the run. */
    private boolean applyCheckpoint(ThinkProcessDocument process, WonkoState state, List<QuestionAnswerDto> answers) {
        boolean goOn = answers.stream()
                .anyMatch(a -> CHECKPOINT_KEY.equals(a.getKey())
                        && a.getOutcome() == QuestionOutcome.ANSWERED
                        && CHECKPOINT_CONTINUE.equalsIgnoreCase(
                                String.valueOf(a.getValue()).trim()));
        if (goOn) {
            state.setCallBudget(state.getCalls() + new WonkoParams(params(process)).maxCalls());
            return false;
        }
        return true;
    }

    /** Answered topics that came from a claim get the claim compared again, with the answer in view. */
    private static void queueRechecks(WonkoState state, WonkoRegister register, QuestionSetDto set) {
        boolean answeredSomething = false;
        for (QuestionDto q : set.getQuestions()) {
            WonkoTopic topic = register.topic(q.getKey()).orElse(null);
            if (topic == null || topic.getStatus() != WonkoTopicStatus.ANSWERED) {
                continue;
            }
            answeredSomething = true;
            String claimId = state.getTopicClaims().get(q.getKey());
            if (claimId != null) {
                WonkoItemHandlers.enqueueRecheck(state, claimId);
            }
            // Claims the model could not judge against the same reference get
            // another look — the answer may be exactly what was missing.
            String ref = topic.getEvidenceRef();
            if (ref != null) {
                register.getTopics().stream()
                        .filter(t -> t.getStatus() == WonkoTopicStatus.UNCLEAR && ref.equals(t.getEvidenceRef()))
                        .map(t -> state.getTopicClaims().get(t.getKey()))
                        .filter(Objects::nonNull)
                        .forEach(id -> WonkoItemHandlers.enqueueRecheck(state, id));
            }
        }
        if (answeredSomething
                && state.getReferencePaths().isEmpty()
                && state.getQueue().stream().noneMatch(i -> i.getType() == WonkoItemType.CHECK)) {
            WonkoItemHandlers.enqueue(state, WonkoItemType.CHECK, 0, null);
        }
    }

    private static void mergeAnswerStat(WonkoState state, WonkoState.RoundStat answered) {
        for (WonkoState.RoundStat stat : state.getStats()) {
            if (stat.getRound() == answered.getRound()) {
                stat.setAnswered(answered.getAnswered());
                stat.setSettled(answered.getSettled());
                stat.setLater(answered.getLater());
                stat.setUnknown(answered.getUnknown());
                return;
            }
        }
    }

    // ──────────────────── The work loop ────────────────────

    /**
     * Works the queue until it is empty, the budget is used up, or a message
     * waits. Asks a question set when the pool is full — and keeps working
     * while the user answers. Afterwards: park on open questions, ask what
     * the pool holds, or finish.
     */
    private void work(ThinkProcessDocument process, ThinkEngineContext ctx, WonkoState state) {
        WonkoParams params = new WonkoParams(params(process));
        WonkoRegister register = loadRegister(process, state);
        WonkoTurn turn = new WonkoTurn(
                process,
                ctx,
                state,
                register,
                params,
                goal(process, state),
                userIdFor(process),
                languageContextResolver.formatBlock(process),
                skills(process, params).names());
        if (!state.getQueue().isEmpty()) {
            thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.RUNNING);
        }
        while (!ctx.hasPending() && !state.isFinished() && state.getCalls() < state.getCallBudget()) {
            List<WonkoItem> batch = nextBatch(state, params.parallelism());
            if (batch.isEmpty()) {
                break;
            }
            runBatch(turn, batch);
            persistState(process, state);
            emitProgress(process, state);
            if (state.getPendingSet() == null && askable(state) >= params.maxQuestionsPerRound()) {
                ask(turn, false);
            }
        }
        if (state.isFinished()) {
            return;
        }
        registerService.save(process.getTenantId(), process.getProjectId(), register);
        if (ctx.hasPending()) {
            persistState(process, state);
            return;
        }
        if (state.getPendingSet() == null) {
            boolean budgetOut = !state.getQueue().isEmpty() && state.getCalls() >= state.getCallBudget();
            boolean asked =
                    (askable(state) > 0 || !state.getProposalPool().isEmpty() || budgetOut) && ask(turn, budgetOut);
            if (state.isFinished()) {
                return;
            }
            if (!asked && state.getQueue().isEmpty()) {
                finish(process, ctx, state, register, "Nothing left worth asking.", "converged");
                return;
            }
        }
        persistState(process, state);
        if (state.getPendingSet() != null) {
            emitProgress(process, state);
            thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.BLOCKED);
        }
    }

    /** The next item — or several compares at once, they run in parallel. */
    private static List<WonkoItem> nextBatch(WonkoState state, int parallelism) {
        List<WonkoItem> sorted = new ArrayList<>(state.getQueue());
        sorted.sort(Comparator.comparingInt(WonkoItem::getPriority));
        if (sorted.isEmpty()) {
            return List.of();
        }
        WonkoItem head = sorted.get(0);
        if (head.getType() != WonkoItemType.COMPARE) {
            return List.of(head);
        }
        return sorted.stream()
                .filter(i -> i.getType() == WonkoItemType.COMPARE && i.getPriority() == head.getPriority())
                .limit(parallelism)
                .toList();
    }

    private void runBatch(WonkoTurn turn, List<WonkoItem> batch) {
        WonkoState state = turn.state;
        state.getQueue().removeAll(batch);
        if (batch.get(0).getType() == WonkoItemType.COMPARE) {
            runCompares(turn, batch);
        } else {
            WonkoItem item = batch.get(0);
            try {
                switch (item.getType()) {
                    case CHECK -> handlers.check(turn);
                    case DECOMPOSE -> handlers.decompose(turn);
                    case LOCATE -> handlers.locate(turn, item);
                    case COVERAGE -> handlers.coverage(turn, item);
                    case COMPARE -> runCompares(turn, batch);
                }
            } catch (RuntimeException e) {
                failed(turn, item, e);
            }
        }
        for (WonkoItem item : batch) {
            WonkoState.ItemCount count =
                    state.getItemCounts().computeIfAbsent(item.getType(), t -> new WonkoState.ItemCount());
            count.setDone(count.getDone() + 1);
            metricService
                    .counter(METRIC_ITEMS, "type", item.getType().name().toLowerCase(Locale.ROOT))
                    .increment();
        }
    }

    /** Compares run their model calls in parallel and apply the results one by one. */
    private void runCompares(WonkoTurn turn, List<WonkoItem> batch) {
        Map<WonkoItem, List<WonkoPassage>> passages = new LinkedHashMap<>();
        for (WonkoItem item : batch) {
            passages.put(item, handlers.comparePassages(turn.state, item));
        }
        Map<WonkoItem, CompletableFuture<Map<String, Object>>> calls = new LinkedHashMap<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (WonkoItem item : batch) {
                LightLlmRequest request = handlers.compareRequest(turn, item, passages.get(item));
                calls.put(
                        item,
                        request == null
                                ? CompletableFuture.completedFuture(Map.of())
                                : CompletableFuture.supplyAsync(() -> handlers.callModel(request), pool));
            }
            for (Map.Entry<WonkoItem, CompletableFuture<Map<String, Object>>> call : calls.entrySet()) {
                WonkoItem item = call.getKey();
                try {
                    handlers.applyCompare(
                            turn, item, passages.get(item), call.getValue().join());
                } catch (RuntimeException e) {
                    failed(turn, item, e);
                }
            }
        }
    }

    /** A failed item is logged with its reason and dropped — never retried forever. */
    private static void failed(WonkoTurn turn, WonkoItem item, RuntimeException e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        log.warn(
                "Wonko id='{}' item {} {} failed: {}",
                turn.process.getId(),
                item.getType(),
                item.getId(),
                cause.toString());
        turn.log(
                item.getType().name().toLowerCase(Locale.ROOT)
                        + (item.getClaimId() == null ? "" : " " + item.getClaimId()),
                "failed",
                cause.getMessage() == null ? cause.toString() : cause.getMessage());
    }

    private static long askable(WonkoState state) {
        return state.getPendingFindings().stream()
                .filter(f -> f.weight() != QuestionWeight.MINOR)
                .count();
    }

    // ──────────────────── Ask ────────────────────

    /**
     * Turns the question pool into one question set (plan §4a.5): the planner
     * applies register, mandate and floor; at most one mandate proposal and,
     * when the budget is used up, the checkpoint go along.
     *
     * @return {@code true} when a set went out
     */
    private boolean ask(WonkoTurn turn, boolean budgetCheckpoint) {
        WonkoState state = turn.state;
        WonkoRegister register = turn.register;
        String stopReason = WonkoConvergence.stopReason(state.getStats(), state.getRound(), turn.params.maxRounds());
        if (stopReason != null) {
            finish(turn.process, turn.ctx, state, register, endNote(stopReason), stopReason);
            return false;
        }
        int round = state.getRound() + 1;
        WonkoQuestionPlanner.Plan plan = WonkoQuestionPlanner.plan(
                register,
                state.getPendingFindings(),
                round,
                turn.params.maxQuestionsPerRound(),
                new HashSet<>(state.getPostponedKeys()));
        // What the planner did not take (deferred, over budget) stays in the pool.
        state.getPendingFindings()
                .removeIf(f -> register.topic(f.key())
                        .map(t -> t.getStatus() != WonkoTopicStatus.OPEN || t.getWeight() == QuestionWeight.MINOR)
                        .orElse(true));

        List<QuestionDto> questions = new ArrayList<>(plan.questions());
        List<WonkoState.PendingProposal> proposals = new ArrayList<>();
        if (!state.getProposalPool().isEmpty()) {
            WonkoCategoryChange change = state.getProposalPool().remove(0).getChange();
            if (change != null) {
                String key = PROPOSAL_KEY_PREFIX + round;
                proposals.add(new WonkoState.PendingProposal(key, change));
                questions.add(proposalQuestion(key, describe(change), change.reason()));
            }
        }
        if (budgetCheckpoint) {
            questions.add(checkpointQuestion(state));
        }
        if (questions.isEmpty()) {
            return false;
        }
        QuestionSetDto set = QuestionSetDto.builder()
                .id(UUID.randomUUID().toString())
                .round(round)
                .intro("Round " + round + " — " + questions.size() + " question(s) about "
                        + String.join(", ", state.getSubjectRefs()) + ".")
                .questions(questions)
                .build();
        state.setRound(round);
        register.setRounds(register.getRounds() + 1);
        state.setPendingSet(set);
        state.setPendingProposals(proposals);
        state.setCheckpointAsked(budgetCheckpoint);
        WonkoState.RoundStat stat = new WonkoState.RoundStat();
        stat.setRound(round);
        stat.setAsked(questions.size());
        stat.setOpenAfter(WonkoReportRenderer.openTopics(register.getTopics()).size());
        state.getStats().add(stat);
        registerService.save(turn.process.getTenantId(), turn.process.getProjectId(), register);
        persistState(turn.process, state);

        appendChat(turn.process, turn.ctx, ChatRole.ASSISTANT, WonkoPrompts.questionMessage(set, plan.decided()), null);
        log.info(
                "Wonko id='{}' asked {} question(s) in round {} (decided {}, suppressed {}, deferred {})",
                turn.process.getId(),
                questions.size(),
                round,
                plan.decided().size(),
                plan.suppressed(),
                plan.deferred());
        return true;
    }

    private static QuestionDto proposalQuestion(String key, String description, @Nullable String reason) {
        return QuestionDto.builder()
                .key(key)
                .text("May I change my mandate: " + description + "?")
                .kind(QuestionKind.CHOICE)
                .options(new ArrayList<>(List.of(
                        QuestionOptionDto.builder()
                                .label(WonkoAnswerApplier.PROPOSAL_YES)
                                .build(),
                        QuestionOptionDto.builder()
                                .label(WonkoAnswerApplier.PROPOSAL_NO)
                                .build())))
                .context(reason)
                .weight(QuestionWeight.IMPORTANT)
                .build();
    }

    private static QuestionDto checkpointQuestion(WonkoState state) {
        return QuestionDto.builder()
                .key(CHECKPOINT_KEY)
                .text("My budget of " + state.getCallBudget() + " model calls is used up and "
                        + state.getQueue().size() + " check item(s) are still open. Go on?")
                .kind(QuestionKind.CHOICE)
                .options(new ArrayList<>(List.of(
                        QuestionOptionDto.builder().label(CHECKPOINT_CONTINUE).build(),
                        QuestionOptionDto.builder().label(CHECKPOINT_STOP).build())))
                .weight(QuestionWeight.IMPORTANT)
                .build();
    }

    private static String describe(WonkoCategoryChange change) {
        return change.op().name().toLowerCase(Locale.ROOT) + " category " + change.category()
                + (change.level() == null ? "" : " to " + change.level().name().toLowerCase(Locale.ROOT))
                + (change.target() == null ? "" : " → " + change.target());
    }

    // ──────────────────── Finish ────────────────────

    private void finish(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            WonkoState state,
            WonkoRegister register,
            String endNote,
            String outcome) {
        String report = WonkoReportRenderer.render(register, endNote, state);
        state.setFinished(true);
        state.setPendingSet(null);
        // A recheck waiting for answers goes with the run; a new message starts the next one.
        state.setRecheckDeferred(false);
        state.getPendingProposals().clear();
        state.setFinalReport(report);
        registerService.save(process.getTenantId(), process.getProjectId(), register);
        persistState(process, state);
        appendChat(process, ctx, ChatRole.ASSISTANT, report, null);
        boolean chat = process.getParentProcessId() == null
                || process.getParentProcessId().isBlank();
        if (chat && !"stopped".equals(outcome) && !state.getFollowUps().isEmpty()) {
            offerFollowUp(process, ctx, state, register);
        }
        // The closed plan box keeps the last state it saw — make that the end state.
        emitProgress(process, state);
        thinkProcessService.setTodos(process.getId(), List.of());
        planModeEventEmitter.emitTodosUpdated(process, List.of());
        metricService.counter(METRIC_OUTCOMES, "outcome", outcome).increment();
        if (process.getParentProcessId() != null
                && !process.getParentProcessId().isBlank()) {
            thinkProcessService.closeProcess(process.getId(), CloseReason.DONE);
        } else {
            // A chat review stays open: a new statement starts the loop again.
            thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
        }
        log.info(
                "Wonko id='{}' finished ({}) after {} question round(s), {} model call(s)",
                process.getId(),
                outcome,
                state.getRound(),
                state.getCalls());
    }

    private static String endNote(String stopReason) {
        return switch (stopReason) {
            case WonkoConvergence.MAX_ROUNDS -> "Stopped after the maximum number of question rounds.";
            case WonkoConvergence.FATIGUE ->
                "Paused: most questions were put off twice in a row. Send a message to go on.";
            case WonkoConvergence.NO_PROGRESS -> "Stopped: the open points did not get fewer for two rounds.";
            default -> stopReason;
        };
    }

    // ──────────────────── Register ────────────────────

    /** Loads the subject's register and, once per run, lays the caller's mandate over the remembered one. */
    private WonkoRegister loadRegister(ThinkProcessDocument process, WonkoState state) {
        WonkoRegister register = registerService.loadOrCreate(
                process.getTenantId(), process.getProjectId(), state.getSubjectKey(), state.getSubjectRefs());
        if (!state.isMandateApplied()) {
            Object raw = params(process).get(PARAM_MANDATE);
            WonkoMandateParser.overlay(
                    register.getMandate(), WonkoMandateParser.parse(raw), WonkoMandateParser.declaresDefault(raw));
            state.setMandateApplied(true);
        }
        return register;
    }

    // ──────────────────── Chat + progress ────────────────────

    private void appendChat(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            ChatRole role,
            String content,
            @Nullable Map<String, Object> meta) {
        if (content.isBlank()) {
            return;
        }
        ChatMessageDocument.ChatMessageDocumentBuilder message = ChatMessageDocument.builder()
                .tenantId(process.getTenantId())
                .sessionId(process.getSessionId())
                .thinkProcessId(process.getId())
                .role(role)
                .content(content);
        if (meta != null) {
            message.meta(meta);
        }
        ctx.chatMessageService().append(message.build());
    }

    /** The queue as TODO list: one line per item type with done/total, plus the open question set. */
    private void emitProgress(ThinkProcessDocument process, WonkoState state) {
        List<TodoItem> items = new ArrayList<>();
        for (Map.Entry<WonkoItemType, WonkoState.ItemCount> e :
                state.getItemCounts().entrySet()) {
            WonkoState.ItemCount c = e.getValue();
            String label = progressLabel(e.getKey()) + " " + c.getDone() + "/" + c.getTotal();
            items.add(TodoItem.builder()
                    .id("wonko-" + e.getKey().name().toLowerCase(Locale.ROOT))
                    .status(c.getDone() >= c.getTotal() ? TodoStatus.COMPLETED : TodoStatus.IN_PROGRESS)
                    .content(label)
                    .activeForm(label)
                    .build());
        }
        QuestionSetDto pending = state.getPendingSet();
        if (pending != null) {
            items.add(TodoItem.builder()
                    .id(TODO_ASK)
                    .status(TodoStatus.IN_PROGRESS)
                    .content(pending.getQuestions().size() + " question(s) to you")
                    .activeForm("Waiting for your answers")
                    .build());
        }
        thinkProcessService.setTodos(process.getId(), items);
        planModeEventEmitter.emitTodosUpdated(process, items);
    }

    private static String progressLabel(WonkoItemType type) {
        return switch (type) {
            case CHECK -> "Check the subject";
            case DECOMPOSE -> "Split into claims";
            case LOCATE -> "Find references";
            case COMPARE -> "Compare with references";
            case COVERAGE -> "Look for missing obligations";
        };
    }

    // ──────────────────── Params + state ────────────────────

    /** The {@code subject}/{@code references} param: one path, a list of paths, or {@code {refs: [...]}}. */
    static List<String> subjectRefs(@Nullable Object raw) {
        if (raw instanceof Map<?, ?> map) {
            return stringList(map.get("refs"));
        }
        return stringList(raw);
    }

    /** A single string or a list of strings as a list; blanks dropped. */
    static List<String> stringList(@Nullable Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof String s && !s.isBlank()) {
            out.add(s.trim());
        } else if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String s && !s.isBlank()) {
                    out.add(s.trim());
                }
            }
        }
        return out;
    }

    private static Map<String, Object> params(ThinkProcessDocument process) {
        Map<String, Object> p = process.getEngineParams();
        return p == null ? Map.of() : p;
    }

    private static String goal(ThinkProcessDocument process, WonkoState state) {
        String goal = process.getGoal();
        if (goal != null && !goal.isBlank()) {
            return goal;
        }
        return state.getGoal() == null ? DEFAULT_GOAL : state.getGoal();
    }

    private @Nullable String userIdFor(ThinkProcessDocument process) {
        return sessionService
                .findBySessionId(process.getSessionId())
                .map(s -> s.getUserId() == null || s.getUserId().isBlank() ? null : s.getUserId())
                .orElse(null);
    }

    private WonkoState loadState(ThinkProcessDocument process) {
        Object raw = params(process).get(STATE_KEY);
        return raw == null ? new WonkoState() : objectMapper.convertValue(raw, WonkoState.class);
    }

    private void persistState(ThinkProcessDocument process, WonkoState state) {
        Map<String, Object> p = new LinkedHashMap<>(params(process));
        p.put(STATE_KEY, objectMapper.convertValue(state, Map.class));
        process.setEngineParams(p);
        thinkProcessService.replaceEngineParams(process.getId(), p);
    }
}
