package de.mhus.vance.brain.ai.light;

import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.AiChatOptions;
import de.mhus.vance.brain.ai.AiModelResolver;
import de.mhus.vance.brain.ai.AiModelService;
import de.mhus.vance.brain.ai.ChatBehavior;
import de.mhus.vance.brain.ai.ChatBehaviorBuilder;
import de.mhus.vance.brain.ai.ModelInfo;
import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.shared.llmusage.LlmUsageService;
import de.mhus.vance.brain.recipe.RecipeLoader;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.util.JsonSchemaLight;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Default implementation of {@link LightLlmService}. Resolves the
 * recipe as config profile, renders the Pebble {@code promptPrefix}
 * with the caller's vars, builds an ad-hoc {@link ChatModel} via the
 * standard {@link ChatBehaviorBuilder} (primary + fallbacks), and —
 * for {@link #callForJson} — runs the Jeltz-style schema-retry loop.
 *
 * <p>No process spawn, no lane lock, no chat-history mongo writes.
 * See {@code specification/light-llm-service.md} for the design.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LightLlmServiceImpl implements LightLlmService {

    static final int DEFAULT_MAX_ATTEMPTS = 3;
    static final String SETTING_DEFAULT_MAX_ATTEMPTS = "lightllm.maxAttempts.default";
    static final String SETTING_ENABLED = "lightllm.enabled";

    static final String METRIC_CALLS = "vance.lightllm.calls";
    static final String METRIC_ATTEMPTS = "vance.lightllm.attempts";
    static final String METRIC_DURATION = "vance.lightllm.duration";

    static final String OUTCOME_SUCCESS = "success";
    static final String OUTCOME_SCHEMA_FAILED = "schema_failed";
    static final String OUTCOME_LLM_ERROR = "llm_error";
    static final String OUTCOME_RECIPE_MISSING = "recipe_missing";
    static final String OUTCOME_REQUEST_INVALID = "request_invalid";
    static final String OUTCOME_DISABLED = "disabled";

    /**
     * Wall-clock ceiling for one light call including retries and
     * fallbacks. Generous enough to ride out a rate-limit blip, short
     * enough to stay inside the timeouts light callers live under (the
     * translation event, for one, is a synchronous HTTP request).
     */
    private static final Duration SYNC_DEADLINE = Duration.ofSeconds(90);

    private final RecipeLoader recipeLoader;
    private final PromptTemplateRenderer templateRenderer;
    private final SettingService settingService;
    private final AiModelResolver aiModelResolver;
    private final AiModelService aiModelService;
    private final ObjectMapper objectMapper;
    private final MetricService metricService;
    private final de.mhus.vance.shared.audit.AuditService auditService;
    private final de.mhus.vance.brain.ai.ModelCatalog modelCatalog;
    private final LlmUsageService llmUsageService;

    @Override
    public String call(LightLlmRequest req) {
        long startNanos = System.nanoTime();
        String recipeName = req == null ? "unknown" : nullToUnknown(req.getRecipeName());
        String outcome = OUTCOME_LLM_ERROR;
        try {
            checkEnabled(req);
            String result = doCall(req);
            outcome = OUTCOME_SUCCESS;
            return result;
        } catch (LightLlmException e) {
            outcome = classify(e);
            throw e;
        } finally {
            recordOutcome(recipeName, outcome, startNanos);
        }
    }

    @Override
    public Map<String, Object> callForJson(LightLlmRequest req) {
        return callForJsonWithModel(req).json();
    }

    @Override
    public LightLlmJsonAnswer callForJsonWithModel(LightLlmRequest req) {
        long startNanos = System.nanoTime();
        String recipeName = req == null ? "unknown" : nullToUnknown(req.getRecipeName());
        String outcome = OUTCOME_LLM_ERROR;
        try {
            checkEnabled(req);
            LightLlmJsonAnswer result = doCallForJson(req);
            outcome = OUTCOME_SUCCESS;
            return result;
        } catch (SchemaValidationException e) {
            outcome = OUTCOME_SCHEMA_FAILED;
            throw e;
        } catch (LightLlmException e) {
            outcome = classify(e);
            throw e;
        } finally {
            recordOutcome(recipeName, outcome, startNanos);
        }
    }

    private String doCall(LightLlmRequest req) {
        validateRequest(req);
        ResolvedRecipe recipe = resolveInternalRecipe(req);
        String systemPrompt = renderSystemPrompt(recipe, req);
        ChatModel chatModel = buildChatModel(recipe, req);

        ChatResponse response;
        try {
            response = chatModel.chat(ChatRequest.builder()
                    .messages(List.of(
                            SystemMessage.from(systemPrompt),
                            UserMessage.from(req.getUserPrompt())))
                    .build());
        } catch (RuntimeException e) {
            throw new LightLlmException("LLM call failed: " + e.getMessage(), e);
        }
        AiMessage reply = response.aiMessage();
        return reply != null && reply.text() != null ? reply.text() : "";
    }

    private LightLlmJsonAnswer doCallForJson(LightLlmRequest req) {
        validateRequest(req);
        ResolvedRecipe recipe = resolveInternalRecipe(req);
        String systemPrompt = renderSystemPrompt(recipe, req);
        BuiltChat built = buildChat(recipe, req);
        ChatModel chatModel = built.model();
        int maxAttempts = effectiveMaxAttempts(req, recipe);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt));
        messages.add(UserMessage.from(req.getUserPrompt()));

        String lastError = null;
        Object lastInvalid = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            ChatResponse response;
            try {
                response = chatModel.chat(
                        ChatRequest.builder().messages(messages).build());
            } catch (RuntimeException e) {
                throw new LightLlmException(
                        "LLM call failed at attempt " + attempt + ": " + e.getMessage(), e);
            }
            AiMessage reply = response.aiMessage();
            String text = reply != null && reply.text() != null ? reply.text() : "";
            if (reply != null) {
                messages.add(reply);
            }

            String json = extractJson(text);
            Object parsed;
            try {
                parsed = objectMapper.readValue(json, Object.class);
            } catch (RuntimeException e) {
                lastError = "reply is not valid JSON: " + e.getMessage();
                lastInvalid = text;
                log.info("LightLlm recipe='{}' attempt {}/{} non-JSON reply ({} chars)",
                        req.getRecipeName(), attempt, maxAttempts, text.length());
                messages.add(UserMessage.from("Schema validation failed: " + lastError));
                continue;
            }
            if (!(parsed instanceof Map<?, ?>)) {
                lastError = "reply must be a JSON object, got "
                        + (parsed == null ? "null" : parsed.getClass().getSimpleName());
                lastInvalid = parsed;
                log.info("LightLlm recipe='{}' attempt {}/{} reply not an object",
                        req.getRecipeName(), attempt, maxAttempts);
                messages.add(UserMessage.from("Schema validation failed: " + lastError));
                continue;
            }
            JsonSchemaLight.Result vr = req.getSchema() == null
                    ? JsonSchemaLight.Result.ok()
                    : JsonSchemaLight.validate(parsed, req.getSchema());
            if (vr.valid()) {
                recordAttempts(req.getRecipeName(), attempt);
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) parsed;
                return new LightLlmJsonAnswer(typed, built.modelName());
            }
            lastError = vr.errorsJoined();
            lastInvalid = parsed;
            log.info("LightLlm recipe='{}' attempt {}/{} schema violation: {}",
                    req.getRecipeName(), attempt, maxAttempts, lastError);
            messages.add(UserMessage.from("Schema validation failed: " + lastError));
        }
        recordAttempts(req.getRecipeName(), maxAttempts);
        throw new SchemaValidationException(maxAttempts, lastInvalid, lastError);
    }

    // ──────────────────── Master switch ────────────────────

    /**
     * Honour the tenant-cascade {@code lightllm.enabled} kill switch.
     * Disabled-tenants get a fast-fail {@link LightLlmException} so
     * callers can fall back to {@code manual_list} / direct lookup.
     */
    private void checkEnabled(@Nullable LightLlmRequest req) {
        String tenantId = req == null ? null : req.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            // request validation will surface the missing tenant —
            // skip the kill-switch check at this point.
            return;
        }
        boolean enabled = settingService.getBooleanValueCascade(
                tenantId,
                req.getProjectId(),
                req.getProcessId(),
                SETTING_ENABLED,
                true /* default */);
        if (!enabled) {
            throw new LightLlmException(
                    "LightLlmService is disabled for this scope ('"
                            + SETTING_ENABLED + "'=false)");
        }
    }

    // ──────────────────── Validation ────────────────────

    private static void validateRequest(@Nullable LightLlmRequest req) {
        if (req == null) {
            throw new LightLlmException("request is null");
        }
        if (req.getRecipeName() == null || req.getRecipeName().isBlank()) {
            throw new LightLlmException("recipeName is required");
        }
        if (req.getUserPrompt() == null || req.getUserPrompt().isBlank()) {
            throw new LightLlmException("userPrompt is required");
        }
        if (req.getTenantId() == null || req.getTenantId().isBlank()) {
            throw new LightLlmException("tenantId is required");
        }
    }

    private ResolvedRecipe resolveInternalRecipe(LightLlmRequest req) {
        ResolvedRecipe r = recipeLoader
                .load(req.getTenantId(), req.getProjectId(), req.getRecipeName())
                .orElseThrow(() -> new LightLlmException(
                        "recipe not found: " + req.getRecipeName()));
        if (!r.internal()) {
            throw new LightLlmException(
                    "recipe '" + req.getRecipeName() + "' is not marked internal:true — "
                            + "LightLlmService only consumes internal config-profile recipes");
        }
        return r;
    }

    // ──────────────────── Prompt rendering ────────────────────

    private String renderSystemPrompt(ResolvedRecipe recipe, LightLlmRequest req) {
        String template = recipe.promptPrefix();
        if (template == null || template.isBlank()) {
            throw new LightLlmException(
                    "recipe '" + req.getRecipeName() + "' has no promptPrefix");
        }
        Map<String, Object> ctx = new HashMap<>();
        if (req.getPebbleVars() != null) {
            ctx.putAll(req.getPebbleVars());
        }
        try {
            return templateRenderer.render(template, ctx);
        } catch (RuntimeException e) {
            throw new LightLlmException(
                    "Pebble render failed for recipe '" + req.getRecipeName() + "': "
                            + e.getMessage(), e);
        }
    }

    // ──────────────────── ChatModel build ────────────────────

    /**
     * A chat model together with the name of the model that answered.
     *
     * <p>The name arrives through a sink rather than as a fixed value,
     * because the answering model is only settled once the call has
     * happened: {@link ResilientChatModel} may have retried onto a
     * fallback entry. {@code primaryName} is what we asked for and stands
     * in when nothing reported back.
     */
    private record BuiltChat(ChatModel model, String primaryName,
            AtomicReference<String> answered) {

        /** The model that answered, falling back to the one asked for. */
        String modelName() {
            String reported = answered.get();
            return reported != null ? reported : primaryName;
        }
    }

    private ChatModel buildChatModel(ResolvedRecipe recipe, LightLlmRequest req) {
        return buildChat(recipe, req).model();
    }

    private BuiltChat buildChat(ResolvedRecipe recipe, LightLlmRequest req) {
        Map<String, Object> params = recipe.params();
        String modelSpec = readModelSpec(params);
        AiChatConfig primary = ChatBehaviorBuilder.resolveOne(
                modelSpec, req.getTenantId(), req.getProjectId(), req.getProcessId(),
                settingService, aiModelResolver);
        List<ChatBehavior.Entry> entries = new ArrayList<>();
        entries.add(new ChatBehavior.Entry(primary, "primary"));
        for (String alias : readFallbacks(params)) {
            try {
                AiChatConfig fb = ChatBehaviorBuilder.resolveOne(
                        alias, req.getTenantId(), req.getProjectId(), req.getProcessId(),
                        settingService, aiModelResolver);
                entries.add(new ChatBehavior.Entry(fb, "fallback:" + alias));
            } catch (RuntimeException e) {
                log.warn("LightLlmService: dropping unreachable fallback '{}' "
                        + "for tenant '{}': {}", alias, req.getTenantId(), e.getMessage());
            }
        }
        ChatBehavior behavior = new ChatBehavior(entries);

        AiChatOptions options = AiChatOptions.builder().build();
        options.setTenantId(req.getTenantId());
        options.setProjectId(req.getProjectId());
        applySamplingParams(options, params);
        // Light calls don't persist to LlmTraceService — the writer hook
        // is repurposed as the audit + usage-ledger emitter. Caller's
        // tenantId/projectId closure is captured here; no session scope.
        String recipeName = recipe.name();
        String tenantId = req.getTenantId();
        String projectId = req.getProjectId();
        String processId = req.getProcessId();
        options.setLlmTraceWriter((request, response, elapsedMs) -> {
            Integer tokensIn = null;
            Integer tokensOut = null;
            if (response != null && response.tokenUsage() != null) {
                tokensIn = response.tokenUsage().inputTokenCount();
                tokensOut = response.tokenUsage().outputTokenCount();
            }
            String modelName = (request.parameters() == null)
                    ? null
                    : request.parameters().modelName();
            auditService.llmLightCall(
                    tenantId, projectId,
                    recipeName, modelName,
                    tokensIn, tokensOut,
                    elapsedMs, response != null, null);
            persistUsage(entries, modelName, tenantId, projectId, processId,
                    recipeName, tokensIn, tokensOut, elapsedMs);
        });

        // Reported by the resilient decorator once a call succeeds, so a
        // fallback is named as the fallback. Reading the name off the
        // response instead would be provider-dependent — most put the
        // model in the client, not the request, and it comes back null.
        AtomicReference<String> answered = new AtomicReference<>();
        options.setSyncAnsweredBy(answered::set);
        // A light call is single-shot and usually has someone waiting on
        // the other end of an HTTP request, so it must not sit in the
        // default retry ladder for minutes. Engines, which do stream, keep
        // the unbounded behaviour.
        options.setSyncCallDeadline(SYNC_DEADLINE);

        AiChat chat = aiModelService.createChat(behavior, options);
        AiChatConfig asked = entries.get(0).config();
        return new BuiltChat(chat.chatModel(),
                asked.providerInstance() + ":" + asked.modelName(),
                answered);
    }

    /**
     * Writes one {@code llm_usage_records} row per light call, so
     * discovery / follow-up / title-generation / triage volume shows up
     * in the usage report instead of vanishing into an audit log line.
     *
     * <p>The behavior may have fallen back to a secondary model, so the
     * provider identity is recovered by matching the model name the
     * request actually carried against the configured entries — only if
     * that fails do we assume the primary. Rows are attributed to the
     * synthetic engine {@link LlmUsageService#ENGINE_LIGHT}; the recipe
     * name carries the concrete caller.
     *
     * <p>Never throws: a bookkeeping failure must not fail the call the
     * user is waiting on.
     *
     * <p>Package-private so the test can drive it directly — the trace
     * writer that invokes it lives inside the chat wrapper, which the
     * unit test replaces with a scripted model.
     */
    void persistUsage(
            List<ChatBehavior.Entry> entries,
            @Nullable String modelName,
            @Nullable String tenantId,
            @Nullable String projectId,
            @Nullable String processId,
            String recipeName,
            @Nullable Integer tokensIn,
            @Nullable Integer tokensOut,
            long elapsedMs) {
        int in = tokensIn == null || tokensIn < 0 ? 0 : tokensIn;
        int out = tokensOut == null || tokensOut < 0 ? 0 : tokensOut;
        if (in <= 0 && out <= 0) return;
        if (tenantId == null || tenantId.isBlank()) return;
        if (entries.isEmpty()) return;
        try {
            AiChatConfig used = entries.stream()
                    .map(ChatBehavior.Entry::config)
                    .filter(c -> c.modelName().equals(modelName))
                    .findFirst()
                    .orElseGet(() -> entries.get(0).config());
            ModelInfo info = modelCatalog.lookupOrDefault(
                    tenantId, projectId,
                    used.providerInstance(), used.provider(), used.modelName());
            ModelInfo.Pricing p = info.pricing();
            llmUsageService.record(LlmUsageService.UsageWrite.builder()
                    .tenantId(tenantId)
                    .projectId(projectId)
                    .sessionId(null)
                    .processId(processId)
                    .recipeName(recipeName)
                    .engineName(LlmUsageService.ENGINE_LIGHT)
                    .providerInstance(used.providerInstance())
                    .providerType(used.provider())
                    .providerModel(used.modelName())
                    .modelAlias(used.providerInstance() + ":" + used.modelName())
                    .tokensIn(in)
                    .tokensOut(out)
                    .cacheReadTokens(0)
                    .cacheWriteTokens(0)
                    .priceInputPerMTok(p == null ? null : p.inputPerMTok())
                    .priceOutputPerMTok(p == null ? null : p.outputPerMTok())
                    .priceCacheReadPerMTok(p == null ? null : p.cacheReadPerMTok())
                    .priceCacheWritePerMTok(p == null ? null : p.cacheWritePerMTok())
                    .currency(p == null ? null : p.currency())
                    .durationMs(elapsedMs)
                    .contextWindowTokens(
                            info.contextWindowTokens() > 0 ? info.contextWindowTokens() : null)
                    .createdAt(java.time.Instant.now())
                    .build());
        } catch (RuntimeException e) {
            log.warn("LightLlm usage persistence failed recipe='{}': {}",
                    recipeName, e.toString());
        }
    }

    // ──────────────────── Param helpers ────────────────────

    private int effectiveMaxAttempts(LightLlmRequest req, ResolvedRecipe recipe) {
        Integer fromRequest = req.getMaxAttempts();
        if (fromRequest != null && fromRequest > 0) {
            return fromRequest;
        }
        Object raw = recipe.params().get("maxAttempts");
        Integer fromRecipe = parsePositiveInt(raw);
        if (fromRecipe != null) {
            return fromRecipe;
        }
        String settingVal = settingService.getStringValueCascade(
                req.getTenantId(), req.getProjectId(), req.getProcessId(),
                SETTING_DEFAULT_MAX_ATTEMPTS);
        Integer fromSetting = parsePositiveInt(settingVal);
        if (fromSetting != null) {
            return fromSetting;
        }
        return DEFAULT_MAX_ATTEMPTS;
    }

    private static @Nullable Integer parsePositiveInt(@Nullable Object raw) {
        if (raw instanceof Number n) {
            int i = n.intValue();
            return i > 0 ? i : null;
        }
        if (raw instanceof String s && !s.isBlank()) {
            try {
                int i = Integer.parseInt(s.trim());
                return i > 0 ? i : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static @Nullable String readModelSpec(Map<String, Object> params) {
        return AiModelResolver.parseModelSpec(params);
    }

    @SuppressWarnings("unchecked")
    private static List<String> readFallbacks(Map<String, Object> params) {
        Object v = params.get("fallbackModels");
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof String s && !s.isBlank()) {
                    out.add(s);
                }
            }
            return out;
        }
        return List.of();
    }

    private static void applySamplingParams(AiChatOptions options, Map<String, Object> params) {
        Object t = params.get("temperature");
        if (t instanceof Number n) {
            options.setTemperature(n.doubleValue());
        } else if (t instanceof String s && !s.isBlank()) {
            try {
                options.setTemperature(Double.parseDouble(s.trim()));
            } catch (NumberFormatException ignored) {
                // leave the AiChatOptions default in place
            }
        }
        Object mt = params.get("maxTokens");
        if (mt instanceof Number n) {
            options.setMaxTokens(n.intValue());
        } else if (mt instanceof String s && !s.isBlank()) {
            try {
                options.setMaxTokens(Integer.parseInt(s.trim()));
            } catch (NumberFormatException ignored) {
                // leave the AiChatOptions default in place
            }
        }
    }

    // ──────────────────── JSON extraction ────────────────────

    // ──────────────────── Metrics ────────────────────

    private void recordOutcome(String recipeName, String outcome, long startNanos) {
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        try {
            metricService.counter(METRIC_CALLS,
                    "recipe", recipeName, "outcome", outcome).increment();
            metricService.timer(METRIC_DURATION,
                    "recipe", recipeName, "outcome", outcome)
                    .record(elapsedMs, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            // Never let metric infrastructure break the call path.
            log.debug("LightLlm metric record failed: {}", e.toString());
        }
    }

    private void recordAttempts(String recipeName, int attempts) {
        try {
            metricService.summary(METRIC_ATTEMPTS, "recipe", recipeName)
                    .record(attempts);
        } catch (RuntimeException e) {
            log.debug("LightLlm attempts-metric failed: {}", e.toString());
        }
    }

    private static String classify(LightLlmException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (msg.startsWith("recipe not found")) return OUTCOME_RECIPE_MISSING;
        if (msg.contains("not marked internal")) return OUTCOME_RECIPE_MISSING;
        if (msg.contains("required") || msg.contains("is null")) return OUTCOME_REQUEST_INVALID;
        if (msg.contains("disabled")) return OUTCOME_DISABLED;
        return OUTCOME_LLM_ERROR;
    }

    private static String nullToUnknown(@Nullable String name) {
        return name == null || name.isBlank() ? "unknown" : name;
    }

    // ──────────────────── JSON extraction ────────────────────

    /**
     * Best-effort JSON extraction from an LLM reply. Strips
     * markdown code fences (```json … ``` or ``` … ```) and falls
     * back to the outermost {@code {…}} substring. Copied verbatim
     * from {@code JeltzEngine.extractJson} — kept local so Jeltz
     * internals can stay package-private; a shared util can absorb
     * both later.
     */
    static String extractJson(@Nullable String text) {
        if (text == null) return "";
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return "";
        if (trimmed.startsWith("```")) {
            int firstNl = trimmed.indexOf('\n');
            if (firstNl > 0) {
                String body = trimmed.substring(firstNl + 1);
                int endFence = body.lastIndexOf("```");
                if (endFence > 0) {
                    return body.substring(0, endFence).trim();
                }
            }
        }
        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1);
        }
        return trimmed;
    }
}
