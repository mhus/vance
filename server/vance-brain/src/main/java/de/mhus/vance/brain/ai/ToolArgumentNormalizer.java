package de.mhus.vance.brain.ai;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import de.mhus.vance.shared.metric.MetricService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Normalises {@code ToolExecutionRequest.arguments} to a clean,
 * single-value JSON object string before the {@link AiMessage} is
 * persisted into chat history.
 *
 * <p><b>Hack-fix for the DeepSeek-V4-Pro tool-call quirk.</b>
 * The OpenAI-compatible endpoint serving {@code openai:deepseek-v4-pro}
 * sometimes emits {@code function.arguments} as a valid JSON object
 * followed by trailing garbage — observed pattern is {@code "{} <extra
 * string>"} for parameter-less tools ({@code manual_list}, {@code whoami},
 * {@code recipe_list}). The same endpoint then rejects its own output
 * on the next turn when the history is replayed:
 * {@code tool_calls[].function.arguments must be a JSON object, but
 * got a non-JSON string} (HTTP 400). Local Jackson parsing also fails
 * on the trailing token. Other strict OpenAI-compatible proxies show
 * the same asymmetric validation, so we normalise here for all models.
 *
 * <p>Strategy: {@link ObjectMapper#readTree(String)} reads exactly
 * one JSON value and (with Jackson's default
 * {@code FAIL_ON_TRAILING_TOKENS=false}) discards what follows. We
 * round-trip the args through that pipeline — trailing garbage drops
 * out, structurally invalid input collapses to {@code "{}"}.
 */
public final class ToolArgumentNormalizer {

    // FAIL_ON_TRAILING_TOKENS is ON by default in Jackson 3 (was OFF in
    // v2). We deliberately turn it back off so the round-trip silently
    // discards the trailing garbage that triggers this whole fix in the
    // first place — that IS the intended behaviour here.
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private static final String EMPTY = "{}";

    public enum Outcome { NOOP, TRIMMED, EMPTIED }

    public record Result(String args, Outcome outcome) {}

    private ToolArgumentNormalizer() {}

    public static Result normalizeWithOutcome(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return new Result(EMPTY, Outcome.EMPTIED);
        }
        try (JsonParser p = MAPPER.tokenStreamFactory().createParser(raw)) {
            JsonNode node = MAPPER.readTree(p);
            if (node == null || !node.isObject()) {
                return new Result(EMPTY, Outcome.EMPTIED);
            }
            String canonical = MAPPER.writeValueAsString(node);
            return canonical.equals(raw)
                    ? new Result(raw, Outcome.NOOP)
                    : new Result(canonical, Outcome.TRIMMED);
        } catch (Exception e) {
            return new Result(EMPTY, Outcome.EMPTIED);
        }
    }

    /**
     * Returns a copy of {@code original} with every
     * {@link ToolExecutionRequest#arguments()} normalised. Returns
     * the same instance when no rewrite was necessary, so callers
     * can short-circuit allocation.
     */
    public static AiMessage normalize(
            AiMessage original, String modelName, @Nullable MetricService metrics) {
        if (!original.hasToolExecutionRequests()) {
            return original;
        }
        List<ToolExecutionRequest> originals = original.toolExecutionRequests();
        List<ToolExecutionRequest> rebuilt = new ArrayList<>(originals.size());
        boolean anyChanged = false;
        for (ToolExecutionRequest call : originals) {
            Result r = normalizeWithOutcome(call.arguments());
            if (r.outcome == Outcome.NOOP) {
                rebuilt.add(call);
                continue;
            }
            anyChanged = true;
            recordOutcome(metrics, modelName, r.outcome);
            rebuilt.add(ToolExecutionRequest.builder()
                    .id(call.id())
                    .name(call.name())
                    .arguments(r.args)
                    .build());
        }
        if (!anyChanged) {
            return original;
        }
        String text = original.text();
        return text == null
                ? AiMessage.from(rebuilt)
                : AiMessage.from(text, rebuilt);
    }

    public static ChatResponse normalize(
            @Nullable ChatResponse raw, String modelName, @Nullable MetricService metrics) {
        if (raw == null || raw.aiMessage() == null) {
            return raw;
        }
        AiMessage normalized = normalize(raw.aiMessage(), modelName, metrics);
        if (normalized == raw.aiMessage()) {
            return raw;
        }
        ChatResponse.Builder b = ChatResponse.builder().aiMessage(normalized);
        // metadata() already contains tokenUsage + finishReason; setting
        // both groups on the builder throws "Cannot set both 'metadata'
        // and 'tokenUsage'". Prefer metadata; only fall back to the
        // discrete setters when no metadata was provided.
        if (raw.metadata() != null) {
            b.metadata(raw.metadata());
        } else {
            if (raw.tokenUsage() != null) b.tokenUsage(raw.tokenUsage());
            if (raw.finishReason() != null) b.finishReason(raw.finishReason());
        }
        return b.build();
    }

    private static void recordOutcome(
            @Nullable MetricService metrics, String modelName, Outcome outcome) {
        if (metrics == null) return;
        metrics.counter(
                        "vance.llm.tool_args_normalized",
                        "outcome", outcome.name().toLowerCase(),
                        "model", modelName == null ? "unknown" : modelName)
                .increment();
    }
}
