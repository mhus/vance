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
import de.mhus.vance.brain.thinkengine.ParentReport;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * (chat meta {@code questionSet}) when the pool is full or the queue has run
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
        return Set.of("doc_read", "doc_list_in_folder");
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
            return ParentReport.of(WonkoPrompts.renderQuestionSet(pending, List.of()));
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
            if (!(msg instanceof SteerMessage.UserChatInput input)) {
                log.debug(
                        "Wonko id='{}' ignoring {}",
                        process.getId(),
                        msg.getClass().getSimpleName());
                continue;
            }
            appendChat(process, ctx, ChatRole.USER, input.content(), null);
            if (state.getSubjectRefs().isEmpty()) {
                if (adoptSubject(process, ctx, state, input)) {
                    seedQueue(process, ctx, state);
                }
                continue;
            }
            if (register == null) {
                register = loadRegister(process, state);
            }
            if (state.getPendingSet() != null) {
                stop |= applyAnswer(process, register, state, input);
                if (state.getPendingSet() != null) {
                    appendChat(
                            process,
                            ctx,
                            ChatRole.ASSISTANT,
                            "I could not map that reply onto the open questions — they are still open.",
                            null);
                }
            } else if (!input.content().isBlank()) {
                // A statement outside a question set: the loop goes again with it.
                state.getUserStatements().add(input.content());
                state.setFinished(false);
                state.setFinalReport(null);
                requeueAfterStatement(state);
            }
        }
        if (register != null) {
            registerService.save(process.getTenantId(), process.getProjectId(), register);
        }
        return stop;
    }

    /** First items of a run: decompose against references, or the whole-subject check. */
    private void seedQueue(ThinkProcessDocument process, ThinkEngineContext ctx, WonkoState state) {
        state.setReferencePaths(
                resolveReferences(ctx, subjectRefs(params(process).get(PARAM_REFERENCES))));
        state.setCallBudget(new WonkoParams(params(process)).maxCalls());
        WonkoItemHandlers.enqueue(
                state, state.getReferencePaths().isEmpty() ? WonkoItemType.CHECK : WonkoItemType.DECOMPOSE, 0, null);
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
            for (WonkoPrompts.UserMandateChange change : interpreted.mandateChanges()) {
                WonkoMandateRules.userSet(
                        register, change.category(), change.level(), change.locked(), state.getRound());
            }
            state.getDecisions().addAll(interpreted.unclear());
            state.getUserStatements().add(input.content());
            answers = interpreted.answers();
            stop = interpreted.stop();
        }
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
                languageContextResolver.formatBlock(process));
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

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(ChatMessageDocument.META_QUESTION_SET, objectMapper.convertValue(set, Map.class));
        appendChat(
                turn.process, turn.ctx, ChatRole.ASSISTANT, WonkoPrompts.renderQuestionSet(set, plan.decided()), meta);
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
        state.getPendingProposals().clear();
        state.setFinalReport(report);
        registerService.save(process.getTenantId(), process.getProjectId(), register);
        persistState(process, state);
        appendChat(process, ctx, ChatRole.ASSISTANT, report, null);
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
