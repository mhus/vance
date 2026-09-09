package de.mhus.vance.brain.ai.fim;

import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.AiChatOptions;
import de.mhus.vance.brain.ai.AiModelResolver;
import de.mhus.vance.brain.ai.AiModelService;
import de.mhus.vance.brain.ai.ChatBehaviorBuilder;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.ai.ModelInfo;
import de.mhus.vance.shared.audit.AuditService;
import de.mhus.vance.shared.llmusage.CallAttribution;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.settings.SettingService;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Fill-In-the-Middle (FIM) completion for cursor-positioned text.
 *
 * <p>Completion-trained models (Qwen-Coder, DeepSeek-Coder, Codestral,
 * StarCoder, …) don't answer prompts — they fill a hole between a
 * prefix and a suffix when the request carries their family's FIM
 * control tokens. This service renders that token shape and issues a
 * single chat call with no system prompt and no JSON contract: raw
 * middle text in, raw continuation out.
 *
 * <h2>Why over the chat wire</h2>
 *
 * vLLM, llama.cpp and LM Studio — where these models usually run — all
 * accept the FIM token sequence as a plain user message on the
 * OpenAI-compatible chat endpoint, so the entire existing plumbing
 * (alias resolution, provider adapters, accounting, audit) is reused
 * and no separate completion endpoint is needed. A dedicated
 * {@code /v1/completions}-with-{@code suffix} transport becomes a
 * provider feature only if a concrete endpoint refuses the chat form.
 *
 * <h2>Configuration</h2>
 *
 * <ul>
 *   <li><b>Gate + model spec:</b> the setting
 *       {@code ai.alias.default.fim}. Absent → FIM is off and callers
 *       fall back to their chat path (previous behaviour, unchanged).
 *       Present → its value is any model spec the
 *       {@link AiModelResolver} accepts, e.g.
 *       {@code lmstudio:qwen3-coder-30b}.</li>
 *   <li><b>Call shape:</b> the model's {@code fimTemplate} quirk —
 *       per-model YAML or a family pattern from
 *       {@code model-quirks.yaml} (Qwen and StarCoder use
 *       {@code <fim_prefix>…<fim_suffix>…<fim_middle>}, DeepSeek-Coder
 *       uses {@code <|fim▁begin|>…<|fim▁hole|>…<|fim▁end|>}, Codestral
 *       uses {@code [PREFIX]…[SUFFIX]…[MIDDLE]}). The template decides
 *       <em>how</em> the model is asked; the setting decides
 *       <em>which</em> model is asked. Deliberately not the other way
 *       round: pointing the alias at a chat model must fail loudly
 *       (missing {@code fimTemplate}), not silently produce garbage
 *       suggestions.</li>
 * </ul>
 *
 * <h2>Result semantics</h2>
 *
 * A blank middle (after end-marker trimming) is a valid outcome — the
 * model judged nothing belongs in the hole — and is returned as an
 * empty string, distinct from "FIM not configured". Callers decide
 * which of the two maps to their fallback behaviour.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FimCompletionService {

    /** Setting that both gates FIM and names the model spec. */
    static final String FIM_ALIAS_SETTING = "ai.alias.default.fim";

    /**
     * Completion models are not creative-writing models — a low
     * temperature keeps the middle deterministic enough to be useful
     * as an inline suggestion.
     */
    static final double FIM_TEMPERATURE = 0.2;

    /**
     * A middle is by design short ("the next few words at the cursor"),
     * so a small output cap keeps latency and cost proportionate.
     * FIM models have no built-in notion of "answer length".
     */
    static final int FIM_MAX_TOKENS = 256;

    /**
     * Wire-level stop hints. The FIM families end the middle with their
     * own markers (`` for Qwen-Coder, pad tokens, plain EOS); a
     * server that honours stop sequences never streams them, one that
     * doesn't gets them trimmed client-side by {@link #END_MARKERS}.
     */
    static final List<String> FIM_STOP_SEQUENCES = List.of("```", "<|fim_pad|>", "<|end▁of▁text|>");

    /** Client-side mirror of the stop hints; first hit truncates. */
    static final List<String> END_MARKERS = FIM_STOP_SEQUENCES;

    /**
     * FIM calls are latency-sensitive — someone is waiting at a
     * cursor — and single-shot, so they must not sit in the default
     * retry ladder for minutes.
     */
    static final Duration SYNC_DEADLINE = Duration.ofSeconds(30);

    static final String METRIC_CALLS = "vance.fim.calls";
    static final String METRIC_DURATION = "vance.fim.duration";

    static final String OUTCOME_SUCCESS = "success";
    static final String OUTCOME_BLANK = "blank";
    static final String OUTCOME_ERROR = "error";

    private final SettingService settingService;
    private final AiModelResolver aiModelResolver;
    private final ModelCatalog modelCatalog;
    private final AiModelService aiModelService;
    private final AuditService auditService;
    private final MetricService metricService;

    /**
     * Whether a FIM model is configured for this scope. Callers use
     * this to pick their path before spending anything on a call.
     */
    public boolean isConfigured(String tenantId, @Nullable String projectId) {
        String spec = fimSpec(tenantId, projectId);
        return spec != null;
    }

    /**
     * Fill the hole between {@code prefix} and {@code suffix}.
     *
     * @param caller   caller identity for attribution, audit and
     *                 metrics (low-cardinality, e.g.
     *                 {@code "follow-up-fim"})
     * @return the trimmed middle text; empty string when the model
     *         produced nothing usable — a valid outcome, not an error
     * @throws FimException when FIM is not configured for the scope
     *         (caller should have checked {@link #isConfigured}), the
     *         resolved model has no {@code fimTemplate} quirk
     *         (fail-closed: a misrouted alias must surface, not
     *         silently degrade), or the provider call failed
     */
    public String completeMiddle(
            String tenantId, @Nullable String projectId, String caller, String prefix, String suffix) {

        String spec = fimSpec(tenantId, projectId);
        if (spec == null) {
            throw new FimException("FIM completion is not configured for tenant '" + tenantId + "' (setting '"
                    + FIM_ALIAS_SETTING + "' is unset)");
        }

        AiChatConfig config =
                ChatBehaviorBuilder.resolveOne(spec, tenantId, projectId, null, settingService, aiModelResolver);
        ModelInfo info = modelCatalog.lookupOrDefault(
                tenantId, projectId, config.providerInstance(), config.provider(), config.modelName());
        String template = info.fimTemplate();
        if (template == null) {
            throw new FimException("Model '" + config.providerInstance() + ":" + config.modelName()
                    + "' has no 'fimTemplate' — the setting '" + FIM_ALIAS_SETTING
                    + "' points at a model without a known Fill-In-the-Middle "
                    + "shape. Set the template in the model's YAML "
                    + "(_vance/model/<provider>/<model>.yaml) or add a family "
                    + "pattern in vance-defaults/model-quirks.yaml.");
        }
        String prompt = renderFimPrompt(template, prefix, suffix);

        long startNanos = System.nanoTime();
        String outcome = OUTCOME_ERROR;
        try {
            AiChat chat = buildChat(tenantId, projectId, caller, config);
            ChatResponse response = chat.chatModel()
                    .chat(ChatRequest.builder()
                            .messages(List.of(UserMessage.from(prompt)))
                            .build());
            String raw = response.aiMessage() != null && response.aiMessage().text() != null
                    ? response.aiMessage().text()
                    : "";
            String middle = trimMiddle(raw);
            outcome = middle.isEmpty() ? OUTCOME_BLANK : OUTCOME_SUCCESS;
            return middle;
        } catch (RuntimeException e) {
            throw new FimException("FIM call failed: " + e.getMessage(), e);
        } finally {
            long elapsedNanos = System.nanoTime() - startNanos;
            metricService
                    .counter(METRIC_CALLS, "outcome", outcome, "caller", caller)
                    .increment();
            metricService.timer(METRIC_DURATION, "caller", caller).record(Duration.ofNanos(elapsedNanos));
        }
    }

    // ──────────────────── Prompt rendering ────────────────────

    /**
     * Splice {@code prefix}/{@code suffix} into the model's FIM
     * template. The template is scanned for the markers — the user
     * content is never scanned, so text that literally contains
     * {@code {suffix}} (template literals in code) is passed through
     * untouched.
     *
     * @throws FimException when the template lost its markers
     *         (defensive — quirk rules are validated at load, a
     *         per-model YAML override is only caught here)
     */
    static String renderFimPrompt(String template, String prefix, String suffix) {
        int prefixIdx = template.indexOf("{prefix}");
        int suffixIdx = template.indexOf("{suffix}");
        if (prefixIdx < 0 || suffixIdx <= prefixIdx) {
            throw new FimException("Malformed fimTemplate '" + template + "' — needs '{prefix}' before '{suffix}'");
        }
        StringBuilder sb = new StringBuilder(template.length() + prefix.length() + suffix.length());
        sb.append(template, 0, prefixIdx);
        sb.append(prefix);
        sb.append(template, prefixIdx + "{prefix}".length(), suffixIdx);
        sb.append(suffix);
        sb.append(template, suffixIdx + "{suffix}".length(), template.length());
        return sb.toString();
    }

    /**
     * Trim the raw continuation at the first FIM end marker and strip
     * surrounding whitespace. Servers that honour stop sequences never
     * deliver a marker; the client-side cut is for the ones that
     * don't.
     */
    static String trimMiddle(@Nullable String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String text = raw;
        for (String marker : END_MARKERS) {
            int idx = text.indexOf(marker);
            if (idx >= 0) {
                text = text.substring(0, idx);
            }
        }
        return text.strip();
    }

    // ──────────────────── Chat build ────────────────────

    private AiChat buildChat(String tenantId, @Nullable String projectId, String caller, AiChatConfig config) {
        AiChatOptions options = AiChatOptions.builder().build();
        options.setTenantId(tenantId);
        options.setProjectId(projectId);
        options.setTemperature(FIM_TEMPERATURE);
        options.setMaxTokens(FIM_MAX_TOKENS);
        options.setStopSequences(FIM_STOP_SEQUENCES);
        options.setSyncCallDeadline(SYNC_DEADLINE);
        // Same audit contract as light LLM calls: the writer hook is
        // the emitter, the accounting decorator inside the provider
        // books the usage ledger.
        options.setLlmTraceWriter((request, response, elapsedMs) -> {
            Integer tokensIn = null;
            Integer tokensOut = null;
            if (response != null && response.tokenUsage() != null) {
                tokensIn = response.tokenUsage().inputTokenCount();
                tokensOut = response.tokenUsage().outputTokenCount();
            }
            String modelName =
                    (request.parameters() == null) ? null : request.parameters().modelName();
            auditService.llmLightCall(
                    tenantId, projectId, caller, modelName, tokensIn, tokensOut, elapsedMs, response != null, null);
        });
        CallAttribution attribution = CallAttribution.light(tenantId, projectId, null, caller);
        return aiModelService.createChat(config, options, attribution);
    }

    /**
     * The configured FIM model spec for this scope, or {@code null}
     * when the setting is unset/blank. This is the single gate:
     * presence of the setting switches callers onto the FIM path,
     * absence keeps them on the chat path (previous behaviour).
     */
    private @Nullable String fimSpec(String tenantId, @Nullable String projectId) {
        String spec = settingService.getStringValueCascade(tenantId, projectId, null, FIM_ALIAS_SETTING);
        return (spec == null || spec.isBlank()) ? null : spec.trim();
    }
}
