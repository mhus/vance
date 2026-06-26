package de.mhus.vance.brain.ai.parser;

import de.mhus.vance.brain.ai.ToolArgumentNormalizer;
import de.mhus.vance.shared.metric.MetricService;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Normalises {@code ToolExecutionRequest.arguments} for models that
 * emit a JSON object followed by trailing garbage (the original
 * DeepSeek-V4-Pro signature: {@code "{} \"manual_list\""} for
 * parameter-less tools). The strict OpenAI-compatible endpoint then
 * rejects its own previous output on the next turn:
 * {@code tool_calls[].function.arguments must be a JSON object, but
 * got a non-JSON string}.
 *
 * <p>Logic lives in {@link ToolArgumentNormalizer} (Jackson
 * {@code FAIL_ON_TRAILING_TOKENS=false} round-trip) — this class is
 * just the SPI hull that exposes it through
 * {@link MessageParserRegistry}.
 *
 * <p>Activation is data-driven through
 * {@code vance-defaults/model-quirks.yaml} (default pattern:
 * {@code deepseek-v4*}). Older releases wrapped this normalizer
 * unconditionally around every chat model — see {@link ToolArgumentNormalizer}
 * Javadoc — but the universal wrap was always a defensive overreach
 * for a single-provider quirk; the SPI restricts it to the actual
 * affected family.
 */
@Component
public class DeepSeekV4MessageParser implements MessageParser {

    public static final String NAME = "deepseek-v4";

    private final @Nullable MetricService metrics;

    public DeepSeekV4MessageParser(@Nullable MetricService metrics) {
        this.metrics = metrics;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ChatResponse parse(ChatResponse raw) {
        // Normalizer keys its metrics off the model name; we don't
        // carry it here (the registry hands us the response only), so
        // we tag with the parser name — the cardinality is bounded by
        // the registered parser set, which Prometheus is happy with.
        return ToolArgumentNormalizer.normalize(raw, NAME, metrics);
    }
}
