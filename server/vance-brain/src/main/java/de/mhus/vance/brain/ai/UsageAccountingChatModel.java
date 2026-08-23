package de.mhus.vance.brain.ai;

import de.mhus.vance.shared.llmusage.CallAttribution;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Books every synchronous round-trip into the usage ledger.
 *
 * <p><b>Why here and not at the call site.</b> Call sites forget: before
 * this decorator existed, Jeltz, Agrajag, Eddie's LLM triage stage and the
 * Cortex deep-validate service all issued real chat calls that never reached
 * the ledger, and Magrathea's agent steps ran on Jeltz. Measuring where the
 * call actually happens is the only place the omission cannot recur.
 *
 * <p><b>Why inside the retry layer.</b> {@link StandardAiChat} stacks
 * {@code Resilient(Logging(Accounting(raw)))}, so this sees every attempt,
 * not just the winner. A retry storm that pushed the full prompt three times
 * costs three prompts, and used to be invisible. It also means each entry of
 * a {@link ChainedAiChat} carries its own decorator with its own model and
 * its own rate snapshot.
 *
 * <p><b>Exactly one per wire call.</b> Double wrapping would not fail
 * visibly — it would produce a plausible, doubled invoice. The decorator is
 * applied in one place only: {@link AbstractChatProvider#createChat}.
 *
 * <p>Never throws. A bookkeeping failure must not break the turn.
 */
public class UsageAccountingChatModel implements ChatModel {

    private static final Logger LOG =
            LoggerFactory.getLogger(UsageAccountingChatModel.class);

    private final ChatModel delegate;
    private final CallAttribution attribution;
    private final ModelInfo modelInfo;
    private final String providerInstance;
    private final UsageSink sink;

    /**
     * Attempts within this chat instance. A fresh {@link AiChat} is built per
     * logical call, so the counter is the attempt number of that call —
     * retries inside {@link ResilientChatModel} re-enter this same instance.
     */
    private final AtomicInteger attempts = new AtomicInteger();

    public UsageAccountingChatModel(
            ChatModel delegate,
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
    public ChatResponse chat(ChatRequest request) {
        int attempt = attempts.incrementAndGet();
        long started = System.currentTimeMillis();
        ChatResponse response;
        try {
            response = delegate.chat(request);
        } catch (RuntimeException e) {
            // The vendor may well have billed the prompt before failing.
            // Booked as FAILED so the report can show it next to the amount
            // instead of adding it in.
            book(UsageAccounting.failed(
                    modelInfo, providerInstance, attempt,
                    System.currentTimeMillis() - started));
            throw e;
        }
        TokenUsage usage = response == null ? null : response.tokenUsage();
        book(UsageAccounting.succeeded(
                modelInfo, providerInstance, attempt, usage,
                System.currentTimeMillis() - started));
        return response;
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
