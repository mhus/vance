package de.mhus.vance.brain.ai;

import de.mhus.vance.shared.llmusage.CallAttribution;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Streaming twin of {@link UsageAccountingChatModel}.
 *
 * <p>The interesting half is {@link StreamingChatResponseHandler#onError}:
 * engines record their metrics <i>after</i> {@code done.get(...)}, so a
 * stream that died mid-flight — timeout, dropped connection, provider 5xx
 * after half the answer — used to consume tokens and leave no trace of the
 * cost. Booking from inside the handler catches it.
 */
public class UsageAccountingStreamingChatModel implements StreamingChatModel {

    private static final Logger LOG =
            LoggerFactory.getLogger(UsageAccountingStreamingChatModel.class);

    private final StreamingChatModel delegate;
    private final CallAttribution attribution;
    private final ModelInfo modelInfo;
    private final String providerInstance;
    private final UsageSink sink;
    private final AtomicInteger attempts = new AtomicInteger();

    public UsageAccountingStreamingChatModel(
            StreamingChatModel delegate,
            CallAttribution attribution,
            ModelInfo modelInfo,
            String providerInstance,
            UsageSink sink) {
        this.delegate = delegate;
        this.attribution = attribution;
        this.modelInfo = modelInfo;
        this.providerInstance = providerInstance;
        this.sink = sink;
    }

    @Override
    public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
        int attempt = attempts.incrementAndGet();
        long started = System.currentTimeMillis();
        delegate.chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partial) {
                handler.onPartialResponse(partial);
            }

            @Override
            public void onPartialThinking(PartialThinking partialThinking) {
                handler.onPartialThinking(partialThinking);
            }

            @Override
            public void onCompleteResponse(ChatResponse complete) {
                TokenUsage usage = complete == null ? null : complete.tokenUsage();
                book(UsageAccounting.succeeded(
                        modelInfo, providerInstance, attempt, usage,
                        System.currentTimeMillis() - started));
                handler.onCompleteResponse(complete);
            }

            @Override
            public void onError(Throwable error) {
                book(UsageAccounting.failed(
                        modelInfo, providerInstance, attempt,
                        System.currentTimeMillis() - started));
                handler.onError(error);
            }
        });
    }

    private void book(@Nullable UsageMeasurement measurement) {
        if (measurement == null) return;
        try {
            sink.onCall(attribution, measurement);
        } catch (RuntimeException e) {
            LOG.warn("Usage accounting threw — ignoring (model='{}'): {}",
                    modelInfo.modelName(), e.toString());
        }
    }
}
