package de.mhus.vance.brain.wonko;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.question.QuestionDto;
import de.mhus.vance.api.question.QuestionKind;
import de.mhus.vance.api.question.QuestionOptionDto;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Wonko — checks a subject for sense, contradictions and gaps and clarifies
 * it with the user in repeated rounds (plan {@code planning/wonko-engine.md}).
 *
 * <p>One round: read the subject, let the {@code wonko-check} recipe report
 * findings, decide what the mandate allows ({@link WonkoQuestionPlanner}), ask
 * the rest as one {@link QuestionSetDto} (chat meta {@code questionSet}) and
 * park. The answer comes back typed on the steer
 * ({@link SteerMessage.UserChatInput#questionAnswers()}); free text is mapped
 * onto the questions by the {@code wonko-interpret} recipe. Then the next
 * round checks the whole subject again, with the register in the prompt so
 * clarified topics do not come back.
 *
 * <p>v1 scope: document subjects, the check as one schema-bound LightLlm call
 * with the subject inlined (no agentic self-clarification yet), chat as the
 * only question channel.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WonkoEngine implements ThinkEngine {

    public static final String NAME = "wonko";
    public static final String VERSION = "0.1.0";
    public static final String STATE_KEY = "wonkoState";

    static final String PARAM_SUBJECT = "subject";
    static final String PARAM_MANDATE = "mandate";
    static final String PARAM_CHECKLIST = "checklist";
    static final String PARAM_CHECK_RECIPE = "checkRecipe";
    static final String PARAM_INTERPRET_RECIPE = "interpretRecipe";
    static final String PARAM_MAX_ROUNDS = "maxRounds";
    static final String PARAM_MAX_QUESTIONS = "maxQuestionsPerRound";

    private static final String DEFAULT_CHECK_RECIPE = "wonko-check";
    private static final String DEFAULT_INTERPRET_RECIPE = "wonko-interpret";
    private static final int DEFAULT_MAX_ROUNDS = 6;
    private static final int DEFAULT_MAX_QUESTIONS = 3;
    private static final String DEFAULT_GOAL = "Check the subject for sense, contradictions and gaps.";

    /** Prefix of question keys that carry a mandate proposal instead of a topic. */
    static final String PROPOSAL_KEY_PREFIX = "mandate-proposal/";

    private static final String TODO_CHECK = "wonko-check";
    private static final String TODO_ASK = "wonko-ask";

    private static final String METRIC_ROUNDS = "vance.wonko.rounds";
    private static final String METRIC_OUTCOMES = "vance.wonko.outcomes";

    private final ThinkProcessService thinkProcessService;
    private final ObjectMapper objectMapper;
    private final LightLlmService lightLlmService;
    private final WonkoRegisterService registerService;
    private final PlanModeEventEmitter planModeEventEmitter;
    private final ProcessEventEmitter eventEmitter;
    private final SessionService sessionService;
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
        return "Checks a subject for sense, contradictions and gaps and clarifies it with the user in rounds.";
    }

    @Override
    public String version() {
        return VERSION;
    }

    /** Rounds wait for the user — an orchestrator must not block on the lane. */
    @Override
    public boolean asyncSteer() {
        return true;
    }

    /** Wonko reads its subject through the permission-checked document tool. */
    @Override
    public Set<String> allowedTools() {
        return Set.of("doc_read");
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
            if (refs.isEmpty()) {
                block(process, ctx, state, "No subject given — set the param `subject` to one or more document paths.");
                return;
            }
            state.setSubjectRefs(refs);
            state.setSubjectKey(WonkoRegisterService.subjectKey(refs));
            persistState(process, state);
        }
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
        // The first round runs on the lane, not on the spawner's thread.
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
     * Drains the inbox, folds answers and statements into state and register,
     * then runs the next round when there is reason to: the first turn after
     * start, an answered question set, or a new statement after the run had
     * finished — the loop always goes again.
     */
    @Override
    public void runTurn(ThinkProcessDocument process, ThinkEngineContext ctx) {
        List<SteerMessage> drained = ctx.drainPending();
        WonkoState state = loadState(process);
        if (state.getSubjectRefs().isEmpty()) {
            return;
        }
        boolean runRound = state.getRound() == 0 && state.getPendingSet() == null && !state.isFinished();
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
                    continue;
                }
            } else if (!input.content().isBlank()) {
                state.getUserStatements().add(input.content());
                state.setFinished(false);
            }
            runRound = true;
        }
        if (register != null) {
            registerService.save(process.getTenantId(), process.getProjectId(), register);
        }
        persistState(process, state);
        if (stop) {
            finish(process, ctx, state, loadRegister(process, state), "Stopped on the user's request.", "stopped");
        } else if (runRound) {
            runRound(process, ctx, state);
        }
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
                "Wonko process " + process.getId() + " " + eventType.name().toLowerCase());
    }

    // ──────────────────── Answers ────────────────────

    /**
     * Folds one reply to the pending set into the register. Typed answers are
     * applied as they are; free text goes through the interpret recipe first.
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
        boolean stop = false;
        WonkoState.RoundStat answered;
        if (typed != null && set.getId().equals(typed.getSetId())) {
            answered = WonkoAnswerApplier.apply(
                    register, set, typed.getAnswers(), state.getPendingProposals(), state.getRound());
        } else {
            WonkoPrompts.InterpretResult interpreted;
            try {
                interpreted = WonkoPrompts.parseInterpret(lightLlmService.callForJson(LightLlmRequest.builder()
                        .recipeName(paramString(process, PARAM_INTERPRET_RECIPE, DEFAULT_INTERPRET_RECIPE))
                        .userPrompt(WonkoPrompts.interpretPrompt(set, input.content()))
                        .schema(WonkoPrompts.interpretSchema())
                        .tenantId(process.getTenantId())
                        .projectId(process.getProjectId())
                        .processId(process.getId())
                        .userId(userIdFor(process))
                        .build()));
            } catch (RuntimeException e) {
                // The questions stay open — nothing of the reply is lost but
                // its mapping; the statement still reaches the next check.
                log.warn("Wonko id='{}' could not interpret a free-text reply: {}", process.getId(), e.toString());
                state.getUserStatements().add(input.content());
                return false;
            }
            for (WonkoPrompts.UserMandateChange change : interpreted.mandateChanges()) {
                WonkoMandateRules.userSet(
                        register, change.category(), change.level(), change.locked(), state.getRound());
            }
            answered = WonkoAnswerApplier.apply(
                    register, set, interpreted.answers(), state.getPendingProposals(), state.getRound());
            state.getUserStatements().add(input.content());
            stop = interpreted.stop();
        }
        mergeAnswerStat(state, answered);
        state.setPendingSet(null);
        state.getPendingProposals().clear();
        return stop;
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

    // ──────────────────── The round ────────────────────

    private void runRound(ThinkProcessDocument process, ThinkEngineContext ctx, WonkoState state) {
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.RUNNING);
        WonkoRegister register = loadRegister(process, state);
        int maxRounds = paramInt(process, PARAM_MAX_ROUNDS, DEFAULT_MAX_ROUNDS);
        String stopReason = WonkoConvergence.stopReason(state.getStats(), state.getRound(), maxRounds);
        if (stopReason != null) {
            finish(process, ctx, state, register, endNote(stopReason), stopReason);
            return;
        }
        int round = state.getRound() + 1;
        setTodos(process, round, TodoStatus.IN_PROGRESS, TodoStatus.PENDING, 0);

        WonkoPrompts.CheckResult check;
        try {
            List<WonkoPrompts.SubjectDoc> subject = readSubject(ctx, state.getSubjectRefs());
            Map<String, Object> json = lightLlmService.callForJson(LightLlmRequest.builder()
                    .recipeName(paramString(process, PARAM_CHECK_RECIPE, DEFAULT_CHECK_RECIPE))
                    .userPrompt(WonkoPrompts.checkPrompt(
                            goal(process),
                            subject,
                            register,
                            paramStrings(process, PARAM_CHECKLIST),
                            state.getUserStatements(),
                            round,
                            maxRounds))
                    .schema(WonkoPrompts.checkSchema())
                    .tenantId(process.getTenantId())
                    .projectId(process.getProjectId())
                    .processId(process.getId())
                    .userId(userIdFor(process))
                    .build());
            check = WonkoPrompts.parseCheck(json);
        } catch (RuntimeException e) {
            log.warn("Wonko id='{}' check round {} failed: {}", process.getId(), round, e.toString());
            clearTodos(process);
            block(process, ctx, state, "The check failed: " + e.getMessage() + "\nSend any message to try again.");
            return;
        }
        metricService.counter(METRIC_ROUNDS).increment();

        List<WonkoState.PendingProposal> proposals = new ArrayList<>();
        List<QuestionDto> proposalQuestions = new ArrayList<>();
        for (WonkoCategoryChange change : check.categoryChanges()) {
            WonkoMandateRules.Result result = WonkoMandateRules.request(register, change, round);
            if (result.outcome() == WonkoMandateRules.Outcome.PROPOSED && proposalQuestions.isEmpty()) {
                String key = PROPOSAL_KEY_PREFIX + proposals.size();
                proposals.add(new WonkoState.PendingProposal(key, change));
                proposalQuestions.add(proposalQuestion(key, result.description(), change.reason()));
            } else if (result.outcome() == WonkoMandateRules.Outcome.REJECTED) {
                log.debug("Wonko id='{}' category change refused: {}", process.getId(), result.description());
            }
        }

        WonkoQuestionPlanner.Plan plan = WonkoQuestionPlanner.plan(
                register, check.findings(), round, paramInt(process, PARAM_MAX_QUESTIONS, DEFAULT_MAX_QUESTIONS));
        state.setRound(round);
        register.setRounds(register.getRounds() + 1);

        List<QuestionDto> questions = new ArrayList<>(plan.questions());
        questions.addAll(proposalQuestions);
        WonkoState.RoundStat stat = new WonkoState.RoundStat();
        stat.setRound(round);
        stat.setAsked(questions.size());
        stat.setOpenAfter(WonkoReportRenderer.openTopics(register.getTopics()).size());
        state.getStats().add(stat);

        if (questions.isEmpty()) {
            finish(process, ctx, state, register, "Nothing left worth asking.", "converged");
            return;
        }

        QuestionSetDto set = QuestionSetDto.builder()
                .id(UUID.randomUUID().toString())
                .round(round)
                .intro("Round " + round + " — " + questions.size() + " question(s) about "
                        + String.join(", ", state.getSubjectRefs()) + ".")
                .questions(questions)
                .build();
        state.setPendingSet(set);
        state.setPendingProposals(proposals);
        registerService.save(process.getTenantId(), process.getProjectId(), register);
        persistState(process, state);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(ChatMessageDocument.META_QUESTION_SET, objectMapper.convertValue(set, Map.class));
        appendChat(process, ctx, ChatRole.ASSISTANT, WonkoPrompts.renderQuestionSet(set, plan.decided()), meta);
        setTodos(process, round, TodoStatus.COMPLETED, TodoStatus.IN_PROGRESS, questions.size());
        // BLOCKED: waiting for an answer. A parent learns about it through the
        // status transition (summarizeForParent renders the questions).
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.BLOCKED);
        log.info(
                "Wonko id='{}' round {} asked {} question(s), decided {}, suppressed {}, deferred {}",
                process.getId(),
                round,
                questions.size(),
                plan.decided().size(),
                plan.suppressed(),
                plan.deferred());
    }

    private void finish(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            WonkoState state,
            WonkoRegister register,
            String endNote,
            String outcome) {
        String report = WonkoReportRenderer.render(register, endNote);
        state.setFinished(true);
        state.setPendingSet(null);
        state.getPendingProposals().clear();
        state.setFinalReport(report);
        registerService.save(process.getTenantId(), process.getProjectId(), register);
        persistState(process, state);
        appendChat(process, ctx, ChatRole.ASSISTANT, report, null);
        clearTodos(process);
        metricService.counter(METRIC_OUTCOMES, "outcome", outcome).increment();
        if (process.getParentProcessId() != null
                && !process.getParentProcessId().isBlank()) {
            thinkProcessService.closeProcess(process.getId(), CloseReason.DONE);
        } else {
            // A chat review stays open: a new statement starts the next round.
            thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
        }
        log.info("Wonko id='{}' finished ({}) after {} round(s)", process.getId(), outcome, state.getRound());
    }

    private void block(ThinkProcessDocument process, ThinkEngineContext ctx, WonkoState state, String diagnosis) {
        persistState(process, state);
        appendChat(process, ctx, ChatRole.ASSISTANT, "Wonko is blocked: " + diagnosis, null);
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.BLOCKED);
        metricService.counter(METRIC_OUTCOMES, "outcome", "blocked").increment();
    }

    private static String endNote(String stopReason) {
        return switch (stopReason) {
            case WonkoConvergence.MAX_ROUNDS -> "Stopped after the maximum number of rounds.";
            case WonkoConvergence.FATIGUE ->
                "Paused: most questions were put off twice in a row. Send a message to go on.";
            case WonkoConvergence.NO_PROGRESS -> "Stopped: the open points did not get fewer for two rounds.";
            default -> stopReason;
        };
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

    // ──────────────────── Subject ────────────────────

    private List<WonkoPrompts.SubjectDoc> readSubject(ThinkEngineContext ctx, List<String> refs) {
        List<WonkoPrompts.SubjectDoc> docs = new ArrayList<>();
        for (String ref : refs) {
            Map<String, Object> result = ctx.tools().invoke("doc_read", Map.of("path", ref));
            Object content = result.get("content");
            if (!(content instanceof String text)) {
                throw new IllegalStateException("subject document '" + ref + "' has no readable text content");
            }
            docs.add(new WonkoPrompts.SubjectDoc(ref, text));
        }
        return docs;
    }

    /** The {@code subject} param: one path, a list of paths, or {@code {refs: [...]}}. */
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

    // ──────────────────── Register ────────────────────

    /**
     * Loads the subject's register and, on the run's first round, lays the
     * caller's mandate over the remembered one.
     */
    private WonkoRegister loadRegister(ThinkProcessDocument process, WonkoState state) {
        WonkoRegister register = registerService.loadOrCreate(
                process.getTenantId(), process.getProjectId(), state.getSubjectKey(), state.getSubjectRefs());
        if (state.getRound() == 0 && state.getPendingSet() == null) {
            Object raw = params(process).get(PARAM_MANDATE);
            WonkoMandateParser.overlay(
                    register.getMandate(), WonkoMandateParser.parse(raw), WonkoMandateParser.declaresDefault(raw));
        }
        return register;
    }

    // ──────────────────── Chat + todos ────────────────────

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

    private void setTodos(ThinkProcessDocument process, int round, TodoStatus check, TodoStatus ask, int questions) {
        List<TodoItem> items = List.of(
                TodoItem.builder()
                        .id(TODO_CHECK)
                        .status(check)
                        .content("Round " + round + ": check the subject")
                        .activeForm("Checking the subject (round " + round + ")")
                        .build(),
                TodoItem.builder()
                        .id(TODO_ASK)
                        .status(ask)
                        .content(questions > 0 ? questions + " question(s) to you" : "Questions to you")
                        .activeForm("Waiting for your answers")
                        .build());
        thinkProcessService.setTodos(process.getId(), items);
        planModeEventEmitter.emitTodosUpdated(process, items);
    }

    private void clearTodos(ThinkProcessDocument process) {
        thinkProcessService.setTodos(process.getId(), List.of());
        planModeEventEmitter.emitTodosUpdated(process, List.of());
    }

    // ──────────────────── Params + state ────────────────────

    private static Map<String, Object> params(ThinkProcessDocument process) {
        Map<String, Object> p = process.getEngineParams();
        return p == null ? Map.of() : p;
    }

    private static String goal(ThinkProcessDocument process) {
        String goal = process.getGoal();
        return goal == null || goal.isBlank() ? DEFAULT_GOAL : goal;
    }

    private static String paramString(ThinkProcessDocument process, String key, String fallback) {
        Object v = params(process).get(key);
        return v instanceof String s && !s.isBlank() ? s : fallback;
    }

    private static int paramInt(ThinkProcessDocument process, String key, int fallback) {
        Object v = params(process).get(key);
        if (v instanceof Number n) {
            return Math.max(1, n.intValue());
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Math.max(1, Integer.parseInt(s.trim()));
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }

    private static List<String> paramStrings(ThinkProcessDocument process, String key) {
        return stringList(params(process).get(key));
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

    @SuppressWarnings("unchecked")
    private void persistState(ThinkProcessDocument process, WonkoState state) {
        Map<String, Object> p = new LinkedHashMap<>(params(process));
        p.put(STATE_KEY, objectMapper.convertValue(state, Map.class));
        process.setEngineParams(p);
        thinkProcessService.replaceEngineParams(process.getId(), p);
    }
}
