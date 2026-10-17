package de.mhus.vance.brain.zem;

import de.mhus.vance.api.zem.ZemAnswerDto;
import de.mhus.vance.api.zem.ZemDecisionDto;
import de.mhus.vance.api.zem.ZemQuestionDto;
import de.mhus.vance.brain.ai.AiModelResolver;
import de.mhus.vance.brain.ai.ChatBehaviorBuilder;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.ai.ModelInfo;
import de.mhus.vance.brain.ai.UsageMeasurement;
import de.mhus.vance.brain.ai.UsageSink;
import de.mhus.vance.brain.ai.decision.AiDecisionConfig;
import de.mhus.vance.brain.ai.decision.AiDecisionException;
import de.mhus.vance.brain.ai.decision.AiDecisionService;
import de.mhus.vance.brain.ai.decision.DecisionModelInfo;
import de.mhus.vance.brain.ai.decision.DecisionRequest;
import de.mhus.vance.brain.ai.decision.DecisionResult;
import de.mhus.vance.brain.recipe.RecipeLoader;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
import de.mhus.vance.brain.tools.ToolInterruptChecker;
import de.mhus.vance.shared.llmusage.CallAttribution;
import de.mhus.vance.shared.llmusage.LlmUsageService;
import de.mhus.vance.shared.llmusage.UsageOutcome;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.settings.SettingService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * The one entry point for decisions (Zem). Two steps, so bulk callers pay
 * the resolution once:
 *
 * <ol>
 *   <li>{@link #prepare} — profile, model alias, catalog entry, credentials,
 *       question validation against the model's limits, throttle limits.
 *       Fails fast with {@link ZemException} before any provider call.</li>
 *   <li>{@link #decide(ZemPreparedCall, Object, ZemUsageAccumulator)} — state
 *       projection, splitting above {@code maxQuestions}, throttled calls with
 *       retry, confidence fill-in, usage booking.</li>
 * </ol>
 *
 * {@link #decide(ZemDecideRequest, Object)} does both for one-off callers.
 *
 * <p><b>Usage:</b> every decision lands in the LLM usage ledger
 * ({@code UsageKind.DECISION}, caller {@code _zem}, recipe = profile name),
 * priced from the catalog's {@code costPerMillionInput}. One-off calls book
 * per call; bulk callers hand in a {@link ZemUsageAccumulator} and book at
 * their own commit points via {@link #flush}. There is deliberately no
 * per-call quota: a decision costs ~$0.00002 (Jev) or nothing (local), and a
 * reserve row per call would cost more than the call — cost visibility is the
 * ledger's job.
 *
 * <p>Design: {@code planning/zem-engine.md} §5–§6.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ZemService {

    public static final String DEFAULT_ALIAS = "default:decision";
    public static final String ENGINE_NAME = "zem";

    public static final String SETTING_ENABLED = "ai.zem.enabled";
    public static final String SETTING_TIMEOUT = "ai.zem.timeout";
    public static final String SETTING_MAX_RETRIES = "ai.zem.max-retries";
    /** Prefix; the provider instance name is appended. */
    public static final String SETTING_MAX_CONCURRENT_PREFIX = "ai.zem.max-concurrent.";
    /** Prefix; the provider instance name is appended. */
    public static final String SETTING_RATE_LIMIT_PREFIX = "ai.zem.rate-limit-rps.";

    static final int DEFAULT_MAX_RETRIES = 3;
    static final String METRIC_CALLS = "vance.zem.calls";
    private static final String API_KEY_SETTING_FMT = "ai.provider.%s.apiKey";
    private static final long BACKOFF_BASE_MILLIS = 500;
    private static final long BACKOFF_MAX_MILLIS = 8_000;

    private final SettingService settingService;
    private final RecipeLoader recipeLoader;
    private final AiModelResolver modelResolver;
    private final ModelCatalog modelCatalog;
    private final AiDecisionService decisionService;
    private final ZemThrottle throttle;
    private final UsageSink usageSink;
    private final MetricService metricService;
    private final ToolInterruptChecker interruptChecker;
    private final de.mhus.vance.brain.ai.power.LlmPowerGate powerGate;

    // ──────────────────── one-off ────────────────────

    /** Prepare and decide in one go, booking usage per call. */
    public ZemDecisionDto decide(ZemDecideRequest request, Object state) {
        return decide(prepare(request), state, null);
    }

    // ──────────────────── prepare ────────────────────

    /**
     * Resolve and validate everything that does not depend on the state.
     *
     * @throws ZemException {@code disabled}, {@code invalid_profile},
     *                      {@code invalid_question}, {@code invalid_choice}
     */
    public ZemPreparedCall prepare(ZemDecideRequest request) {
        String tenantId = request.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        String projectId = request.getProjectId();
        String processId = request.getProcessId();
        String powerOff = powerGate.refusal(tenantId, null, null, processId);
        if (powerOff != null) {
            throw new ZemException(ZemException.Reason.DISABLED, powerOff);
        }
        if (!settingService.getBooleanValueCascade(tenantId, projectId, processId, SETTING_ENABLED, true)) {
            throw new ZemException(
                    ZemException.Reason.DISABLED, "Decisions are disabled in this scope (" + SETTING_ENABLED + ")");
        }

        ZemProfile profile =
                request.getProfile() == null || request.getProfile().isBlank()
                        ? null
                        : loadProfile(tenantId, projectId, request.getProfile().trim());
        List<ZemQuestionDto> questions =
                ZemQuestionValidator.merge(profile == null ? List.of() : profile.questions(), request.getQuestions());

        String alias = firstNonBlank(request.getModel(), profile == null ? null : profile.model(), DEFAULT_ALIAS);
        AiModelResolver.Resolved resolved = resolve(alias, tenantId, projectId, processId);
        DecisionModelInfo model = modelCatalog
                .lookupDecision(tenantId, projectId, resolved.providerInstance(), resolved.modelName())
                .or(() -> modelCatalog.lookupDecision(tenantId, projectId, resolved.provider(), resolved.modelName()))
                .orElseThrow(() -> new ZemException(
                        ZemException.Reason.INVALID_CHOICE,
                        "'" + alias + "' resolves to " + resolved.providerInstance() + ":" + resolved.modelName()
                                + ", which has no decision model entry — add it to the model catalog with"
                                + " kind: decision, or set ai.alias.default.decision"));
        if (!decisionService.canServe(resolved.providerInstance(), resolved.provider())) {
            throw new ZemException(
                    ZemException.Reason.INVALID_CHOICE,
                    "No decision adapter serves provider instance '" + resolved.providerInstance() + "' (protocol "
                            + resolved.provider() + ")");
        }

        List<ZemQuestionDto> validated = ZemQuestionValidator.validate(questions, model);
        AiDecisionConfig config = new AiDecisionConfig(
                resolved.provider(),
                resolved.providerInstance(),
                resolved.modelName(),
                settingService.getDecryptedPasswordCascade(
                        tenantId,
                        projectId,
                        processId,
                        String.format(API_KEY_SETTING_FMT, resolved.providerInstance())),
                ChatBehaviorBuilder.resolveBaseUrl(
                        resolved.providerInstance(), tenantId, projectId, processId, settingService),
                positiveInt(tenantId, projectId, processId, SETTING_TIMEOUT, model.timeoutSeconds()));

        List<String> stateSelect = request.getStateSelect() != null
                ? request.getStateSelect()
                : profile == null ? List.of() : profile.stateSelect();
        String instance = resolved.providerInstance();
        Integer concurrent = optionalPositiveInt(
                tenantId, projectId, processId, SETTING_MAX_CONCURRENT_PREFIX + instance, model.maxConcurrentCalls());
        Double rps = optionalPositiveDouble(
                tenantId, projectId, processId, SETTING_RATE_LIMIT_PREFIX + instance, model.rateLimitRps());
        int maxRetries = nonNegativeInt(tenantId, projectId, processId, SETTING_MAX_RETRIES, DEFAULT_MAX_RETRIES);

        return new ZemPreparedCall(
                tenantId,
                projectId,
                processId,
                request.getUserId(),
                profile == null ? null : profile.name(),
                alias,
                config,
                model,
                validated,
                stateSelect,
                maxRetries,
                concurrent,
                rps);
    }

    // ──────────────────── decide ────────────────────

    /**
     * Decide one state. Thread-safe; bulk callers call this from many
     * threads with one prepared call.
     *
     * @param usage {@code null} = book this call to the ledger now;
     *              otherwise add it to the accumulator (bulk path)
     * @throws ZemException {@code invalid_state}, provider-side reasons,
     *                      {@code cancelled}
     */
    public ZemDecisionDto decide(ZemPreparedCall call, Object state, @Nullable ZemUsageAccumulator usage) {
        if (!(state instanceof String || state instanceof Map<?, ?> || state instanceof List<?>)) {
            throw new ZemException(
                    ZemException.Reason.INVALID_STATE,
                    "state must be a string, map or list"
                            + (state == null ? "" : ", got " + state.getClass().getSimpleName()));
        }
        if (usage != null && usage.call() != call) {
            throw new IllegalArgumentException("accumulator belongs to another prepared call");
        }
        Object selected = ZemStateSelector.select(state, call.stateSelect());
        List<List<ZemQuestionDto>> parts =
                partition(call.questions(), call.model().maxQuestions());

        long start = System.currentTimeMillis();
        List<ZemAnswerDto> answers = new ArrayList<>(call.questions().size());
        String reportedModel = null;
        long tokens = 0;
        boolean anyTokens = false;
        Double cost = null;
        try {
            for (List<ZemQuestionDto> part : parts) {
                DecisionResult result = callWithRetry(call, new DecisionRequest(selected, part));
                if (reportedModel == null) {
                    reportedModel = result.model();
                }
                answers.addAll(result.answers());
                if (result.inputTokens() != null) {
                    tokens += result.inputTokens();
                    anyTokens = true;
                }
                if (result.costUsd() != null) {
                    cost = (cost == null ? 0 : cost) + result.costUsd();
                }
            }
        } catch (ZemException e) {
            long failedAfter = System.currentTimeMillis() - start;
            count(call, e.reason().wire());
            if (usage != null) {
                usage.addFailure(failedAfter);
            } else {
                book(call, 0, 0, UsageOutcome.FAILED, failedAfter);
            }
            throw e;
        }
        long latency = System.currentTimeMillis() - start;
        answers.replaceAll(ZemConfidence::fill);

        count(call, "success");
        if (usage != null) {
            usage.addSuccess(1, tokens, latency);
        } else {
            book(call, tokens, 1, UsageOutcome.SUCCESS, latency);
        }
        return ZemDecisionDto.builder()
                .model(reportedModel == null ? call.config().modelName() : reportedModel)
                .answers(answers)
                .inputTokens(anyTokens ? tokens : null)
                .costUsd(cost)
                .latencyMs(latency)
                .build();
    }

    // ──────────────────── bulk usage ────────────────────

    /** Start an accumulator for {@code call} (bulk path). */
    public ZemUsageAccumulator openAccumulator(ZemPreparedCall call) {
        return new ZemUsageAccumulator(call);
    }

    /** Book everything accumulated since the last flush as one ledger write per outcome. */
    public void flush(ZemUsageAccumulator usage) {
        ZemUsageAccumulator.Snapshot s = usage.drain();
        if (s.decisions() > 0) {
            book(usage.call(), s.inputTokens(), s.decisions(), UsageOutcome.SUCCESS, s.durationMs());
        }
        if (s.failedCalls() > 0) {
            book(usage.call(), 0, s.failedCalls(), UsageOutcome.FAILED, s.failedDurationMs());
        }
    }

    // ──────────────────── internals ────────────────────

    private ZemProfile loadProfile(String tenantId, @Nullable String projectId, String name) {
        ResolvedRecipe recipe;
        try {
            recipe = recipeLoader
                    .load(tenantId, projectId, name)
                    .orElseThrow(() -> new ZemException(
                            ZemException.Reason.INVALID_PROFILE, "Unknown Zem profile '" + name + "'"));
        } catch (RecipeLoader.RecipeParseException e) {
            throw new ZemException(
                    ZemException.Reason.INVALID_PROFILE,
                    "Zem profile '" + name + "' cannot be parsed: " + e.getMessage(),
                    e);
        }
        if (!ENGINE_NAME.equals(recipe.engine())) {
            throw new ZemException(
                    ZemException.Reason.INVALID_PROFILE,
                    "Recipe '" + name + "' is not a Zem profile (engine: " + recipe.engine() + ", expected zem)");
        }
        return ZemProfileParser.parse(recipe.name(), recipe.params());
    }

    private AiModelResolver.Resolved resolve(
            String alias, String tenantId, @Nullable String projectId, @Nullable String processId) {
        try {
            return modelResolver.resolveOrDefault(alias, tenantId, projectId, processId);
        } catch (RuntimeException e) {
            throw new ZemException(
                    ZemException.Reason.INVALID_CHOICE,
                    "Cannot resolve decision model '" + alias + "': " + e.getMessage(),
                    e);
        }
    }

    /** Throttled call with retry on retryable failures; maps the last failure. */
    private DecisionResult callWithRetry(ZemPreparedCall call, DecisionRequest request) {
        for (int attempt = 0; ; attempt++) {
            interruptChecker.throwIfHalted(call.processId());
            try (ZemThrottle.Permit ignored =
                    throttle.acquire(call.throttleKey(), call.maxConcurrent(), call.rateLimitRps())) {
                return decisionService.decide(call.config(), request);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ZemException(ZemException.Reason.CANCELLED, "Decision call interrupted", e);
            } catch (AiDecisionException e) {
                if (!e.retryable() || attempt >= call.maxRetries()) {
                    throw map(e);
                }
                log.debug(
                        "Zem: retrying {} after {} (attempt {}/{})",
                        call.config().fullName(),
                        e.failure(),
                        attempt + 1,
                        call.maxRetries());
                sleep(backoff(e, attempt));
            }
        }
    }

    private static Duration backoff(AiDecisionException e, int attempt) {
        if (e.retryAfter() != null) {
            return e.retryAfter();
        }
        long exp = Math.min(BACKOFF_MAX_MILLIS, BACKOFF_BASE_MILLIS << Math.min(attempt, 10));
        return Duration.ofMillis(exp / 2 + ThreadLocalRandom.current().nextLong(exp / 2 + 1));
    }

    private static void sleep(Duration wait) {
        try {
            Thread.sleep(wait.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ZemException(ZemException.Reason.CANCELLED, "Decision retry interrupted", e);
        }
    }

    static ZemException map(AiDecisionException e) {
        ZemException.Reason reason =
                switch (e.failure()) {
                    case INVALID_REQUEST -> ZemException.Reason.INVALID_REQUEST;
                    case MODEL_NOT_FOUND, NO_ADAPTER -> ZemException.Reason.INVALID_CHOICE;
                    case AUTH -> ZemException.Reason.AUTH;
                    case RATE_LIMITED -> ZemException.Reason.RATE_LIMITED;
                    case TIMEOUT -> ZemException.Reason.TIMEOUT;
                    case PROVIDER_UNAVAILABLE, TRANSPORT, PROTOCOL -> ZemException.Reason.PROVIDER_ERROR;
                    case INTERRUPTED -> ZemException.Reason.CANCELLED;
                };
        return new ZemException(reason, e.getMessage(), e);
    }

    static List<List<ZemQuestionDto>> partition(List<ZemQuestionDto> questions, int size) {
        if (questions.size() <= size) {
            return List.of(questions);
        }
        List<List<ZemQuestionDto>> parts = new ArrayList<>();
        for (int i = 0; i < questions.size(); i += size) {
            parts.add(questions.subList(i, Math.min(questions.size(), i + size)));
        }
        return parts;
    }

    private void book(ZemPreparedCall call, long inputTokens, int decisions, UsageOutcome outcome, long durationMs) {
        DecisionModelInfo model = call.model();
        ModelInfo.Pricing pricing = model.costPerMillionInput() == null
                ? null
                : new ModelInfo.Pricing("USD", model.costPerMillionInput(), 0.0, null, null);
        usageSink.onCall(
                new CallAttribution(
                        call.tenantId(),
                        call.projectId(),
                        /* sessionId */ null,
                        call.processId(),
                        LlmUsageService.CALLER_ZEM,
                        call.profileName()),
                UsageMeasurement.decision(
                        call.config().providerInstance(),
                        call.config().provider(),
                        call.config().modelName(),
                        pricing,
                        (int) Math.min(Integer.MAX_VALUE, inputTokens),
                        decisions,
                        outcome,
                        durationMs));
    }

    private void count(ZemPreparedCall call, String outcome) {
        metricService
                .counter(METRIC_CALLS, "outcome", outcome, "model", call.alias())
                .increment();
    }

    // ──────────────────── settings ────────────────────

    private int positiveInt(
            String tenantId, @Nullable String projectId, @Nullable String processId, String key, int fallback) {
        Integer v = parseInt(settingService.getStringValueCascade(tenantId, projectId, processId, key), key);
        return v != null && v > 0 ? v : fallback;
    }

    private int nonNegativeInt(
            String tenantId, @Nullable String projectId, @Nullable String processId, String key, int fallback) {
        Integer v = parseInt(settingService.getStringValueCascade(tenantId, projectId, processId, key), key);
        return v != null && v >= 0 ? v : fallback;
    }

    private @Nullable Integer optionalPositiveInt(
            String tenantId,
            @Nullable String projectId,
            @Nullable String processId,
            String key,
            @Nullable Integer fallback) {
        Integer v = parseInt(settingService.getStringValueCascade(tenantId, projectId, processId, key), key);
        return v != null && v > 0 ? v : fallback;
    }

    private @Nullable Double optionalPositiveDouble(
            String tenantId,
            @Nullable String projectId,
            @Nullable String processId,
            String key,
            @Nullable Double fallback) {
        String raw = settingService.getStringValueCascade(tenantId, projectId, processId, key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            double v = Double.parseDouble(raw.trim());
            return v > 0 ? v : fallback;
        } catch (NumberFormatException e) {
            log.warn("Zem: non-numeric setting '{}' = '{}' — using the catalog default", key, raw);
            return fallback;
        }
    }

    private static @Nullable Integer parseInt(@Nullable String raw, String key) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("Zem: non-numeric setting '{}' = '{}' — using the default", key, raw);
            return null;
        }
    }

    private static String firstNonBlank(@Nullable String a, @Nullable String b, String fallback) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        if (b != null && !b.isBlank()) {
            return b.trim();
        }
        return fallback;
    }
}
