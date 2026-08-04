package de.mhus.vance.brain.guard;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.brain.recipe.GuardConfig;
import de.mhus.vance.brain.recipe.GuardTrigger;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.SteerMessageCodec;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Engine-agnostic completion guard: at an engine's yield point (Frankie
 * stop-paths, Arthur/Eddie post-reply, …) it runs each configured guard's
 * judge; on {@code fire=true} it injects the guard's fixed follow-up
 * prompt into the process's own pending queue and schedules a lane turn,
 * so the process continues instead of yielding.
 *
 * <p>Guards come from the recipe {@code guard:} block plus an additive
 * per-process runtime override (both {@code guardJudgeOverride} and
 * {@code guardPromptOverride} set). Backstops: a per-guard
 * {@code maxRounds} cap against the process's {@code guardRounds}
 * counter, and fail-open on any judge error (never blocks the engine).
 *
 * <p>See {@code planning/completion-guard.md}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CompletionGuardService {

    /** Internal LightLlm recipe that renders the judge and returns {@code {fire,reason}}. */
    static final String JUDGE_RECIPE = "completion-guard";

    /** Sender id stamped on injected pending messages. */
    static final String INJECT_SENDER = "_guard";

    /** Default round cap for a runtime-override guard. */
    static final int RUNTIME_MAX_ROUNDS = 3;

    private static final String METRIC = "vance.guard.evaluations";

    private static final Map<String, Object> JUDGE_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "fire", Map.of("type", "boolean"),
                    "reason", Map.of("type", "string")),
            "required", List.of("fire"));

    private final RecipeResolver recipeResolver;
    private final LightLlmService lightLlm;
    private final ThinkProcessService thinkProcessService;
    private final ChatMessageService chatMessageService;
    private final ProcessEventEmitter eventEmitter;
    private final MetricService metrics;

    /**
     * Evaluates all applicable guards for {@code process} at a yield
     * point. On the first firing guard, injects its prompt and returns
     * {@link GuardEvaluation#fired}. First-fire-wins — remaining guards
     * are re-checked on the next completion.
     *
     * @param naturalStop {@code true} for a natural stop, {@code false}
     *                    for an explicit terminate — matched against each
     *                    guard's {@link GuardTrigger}
     */
    public GuardEvaluation evaluate(
            ThinkProcessDocument process, @Nullable String finalOutput, boolean naturalStop) {
        List<GuardConfig> guards = resolveGuards(process);
        log.trace("Guard evaluate id='{}' naturalStop={} guardRounds={} resolvedGuards={}",
                process.getId(), naturalStop, process.getGuardRounds(), guards.size());
        if (guards.isEmpty()) {
            return GuardEvaluation.noop();
        }
        for (GuardConfig guard : guards) {
            boolean triggerMatch = naturalStop
                    ? guard.trigger().firesOnNaturalStop()
                    : guard.trigger().firesOnTerminate();
            if (!triggerMatch) {
                log.trace("Guard id='{}' skip — trigger={} does not match naturalStop={}",
                        process.getId(), guard.trigger(), naturalStop);
                continue;
            }
            if (process.getGuardRounds() >= guard.maxRounds()) {
                log.trace("Guard id='{}' skip — round-cap reached ({} >= {})",
                        process.getId(), process.getGuardRounds(), guard.maxRounds());
                continue;
            }
            log.trace("Guard id='{}' running judge — judge='{}'",
                    process.getId(), abbreviate(guard.judge()));
            JudgeResult judged = runJudge(process, guard, finalOutput);
            if (judged == null) {
                // fail-open — judge error already logged + metered
                continue;
            }
            log.trace("Guard id='{}' judge returned fire={} reason='{}'",
                    process.getId(), judged.fire(), judged.reason());
            if (!judged.fire()) {
                continue;
            }
            int round = thinkProcessService.incrementGuardRounds(process.getId());
            inject(process, guard.prompt());
            eventEmitter.scheduleTurn(process.getId());
            metrics.counter(METRIC, "outcome", "fired").increment();
            log.info("Completion guard fired id='{}' round={} reason='{}'",
                    process.getId(), round, judged.reason());
            return GuardEvaluation.fired(guard, judged.reason());
        }
        log.trace("Guard evaluate id='{}' — all applicable guards passed", process.getId());
        metrics.counter(METRIC, "outcome", "passed").increment();
        return GuardEvaluation.passed();
    }

    /**
     * Resets the per-process guard round budget when {@code inbox} carries
     * genuine (non-guard-injected) user input. A single-action chat engine
     * (Arthur, Eddie) is long-lived, so without this the lifetime round
     * counter would climb across user turns and permanently disable the
     * guard after {@code maxRounds} fires. Each fresh user request restarts
     * the "are you really done?" negotiation with a full budget. No-op when
     * the counter is already zero or the turn is the guard's own follow-up.
     */
    public void resetIfUserTurn(ThinkProcessDocument process, List<SteerMessage> inbox) {
        if (process.getGuardRounds() <= 0 || inbox == null) {
            return;
        }
        for (SteerMessage m : inbox) {
            if (m instanceof SteerMessage.UserChatInput uci
                    && uci.content() != null && !uci.content().isBlank()
                    && !INJECT_SENDER.equals(uci.fromUser())) {
                thinkProcessService.resetGuardRounds(process.getId());
                log.trace("Guard rounds reset id='{}' — genuine user turn", process.getId());
                return;
            }
        }
    }

    /**
     * The effective guards for a process: recipe {@code guard:} block plus
     * the additive runtime override (active only when both override fields
     * are set). Also used by the {@code guard} command's {@code get}.
     */
    public List<GuardConfig> resolveGuards(ThinkProcessDocument process) {
        List<GuardConfig> out = new ArrayList<>();
        String recipeName = process.getRecipeName();
        if (recipeName != null && !recipeName.isBlank()) {
            try {
                recipeResolver.resolve(process.getTenantId(), process.getProjectId(), recipeName)
                        .ifPresent(recipe -> out.addAll(recipe.guards()));
            } catch (RuntimeException e) {
                log.warn("Completion guard id='{}' recipe='{}' resolve failed: {}",
                        process.getId(), recipeName, e.toString());
            }
        }
        int recipeGuards = out.size();
        String judge = process.getGuardJudgeOverride();
        String prompt = process.getGuardPromptOverride();
        boolean runtimeActive = judge != null && !judge.isBlank()
                && prompt != null && !prompt.isBlank();
        if (runtimeActive) {
            out.add(new GuardConfig(judge, prompt, GuardTrigger.STOP, RUNTIME_MAX_ROUNDS));
        }
        log.trace("Guard resolveGuards id='{}' recipe='{}' recipeGuards={} "
                        + "runtimeOverride active={} (judgeSet={}, promptSet={})",
                process.getId(), process.getRecipeName(), recipeGuards, runtimeActive,
                judge != null && !judge.isBlank(), prompt != null && !prompt.isBlank());
        return out;
    }

    private @Nullable JudgeResult runJudge(
            ThinkProcessDocument process, GuardConfig guard, @Nullable String finalOutput) {
        try {
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("judge", guard.judge());
            vars.put("task", firstUserInput(process));
            vars.put("output", finalOutput == null ? "" : finalOutput);
            Map<String, Object> raw = lightLlm.callForJson(LightLlmRequest.builder()
                    .recipeName(JUDGE_RECIPE)
                    .userPrompt("Evaluate the guard condition.")
                    .pebbleVars(vars)
                    .schema(JUDGE_SCHEMA)
                    .tenantId(process.getTenantId())
                    .projectId(process.getProjectId())
                    .processId(process.getId())
                    .build());
            log.trace("Guard judge id='{}' recipe='{}' outputLen={} raw={}",
                    process.getId(), JUDGE_RECIPE,
                    finalOutput == null ? 0 : finalOutput.length(), raw);
            boolean fire = asBool(raw.get("fire"));
            Object reason = raw.get("reason");
            return new JudgeResult(fire, reason == null ? "" : reason.toString());
        } catch (RuntimeException e) {
            log.warn("Completion guard id='{}' judge failed — fail-open: {}",
                    process.getId(), e.toString());
            metrics.counter(METRIC, "outcome", "judge_error").increment();
            return null;
        }
    }

    private void inject(ThinkProcessDocument process, String prompt) {
        String content = "[completion-guard] " + prompt;
        SteerMessage.UserChatInput injected = new SteerMessage.UserChatInput(
                Instant.now(), null, INJECT_SENDER, content);
        thinkProcessService.appendPending(
                process.getId(), SteerMessageCodec.toDocument(injected));
    }

    private String firstUserInput(ThinkProcessDocument process) {
        try {
            List<ChatMessageDocument> history = chatMessageService.activeHistory(
                    process.getTenantId(), process.getSessionId(), process.getId());
            for (ChatMessageDocument m : history) {
                if (m.getRole() == ChatRole.USER
                        && m.getContent() != null && !m.getContent().isBlank()) {
                    return m.getContent();
                }
            }
        } catch (RuntimeException e) {
            log.debug("Completion guard firstUserInput lookup failed id='{}': {}",
                    process.getId(), e.toString());
        }
        String goal = process.getGoal();
        return goal == null ? "" : goal;
    }

    private static boolean asBool(@Nullable Object v) {
        return v instanceof Boolean b ? b : "true".equalsIgnoreCase(String.valueOf(v));
    }

    private static String abbreviate(@Nullable String s) {
        if (s == null) return "";
        return s.length() <= 80 ? s : s.substring(0, 80) + "…";
    }

    private record JudgeResult(boolean fire, String reason) {}
}
