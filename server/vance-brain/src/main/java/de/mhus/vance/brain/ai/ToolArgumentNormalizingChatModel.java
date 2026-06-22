package de.mhus.vance.brain.ai;

import de.mhus.vance.shared.metric.MetricService;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.jspecify.annotations.Nullable;

/**
 * Sync {@link ChatModel} decorator that runs every response through
 * {@link ToolArgumentNormalizer} before handing it back. Hack-fix for
 * the DeepSeek-V4-Pro {@code function.arguments} quirk — see the
 * normalizer's class-Javadoc for the full story.
 *
 * <p>Wrapped unconditionally for every model so the fix also catches
 * future providers that exhibit the same asymmetric-validation
 * behaviour. The normalizer is a no-op when args are already clean.
 */
public class ToolArgumentNormalizingChatModel implements ChatModel {

    private final ChatModel delegate;
    private final String modelName;
    private final @Nullable MetricService metrics;

    public ToolArgumentNormalizingChatModel(
            ChatModel delegate, String modelName, @Nullable MetricService metrics) {
        this.delegate = delegate;
        this.modelName = modelName;
        this.metrics = metrics;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        return ToolArgumentNormalizer.normalize(delegate.chat(request), modelName, metrics);
    }
}
