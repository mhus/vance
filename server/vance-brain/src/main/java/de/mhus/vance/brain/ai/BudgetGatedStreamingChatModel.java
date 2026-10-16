package de.mhus.vance.brain.ai;

import de.mhus.vance.shared.llmusage.CallAttribution;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

/**
 * Streaming twin of {@link BudgetGatedChatModel}. A refusal is delivered
 * through {@link StreamingChatResponseHandler#onError} — the streaming
 * contract's failure path — so the resilience layer sees it exactly like a
 * provider error that arrived before the first token.
 */
public class BudgetGatedStreamingChatModel implements StreamingChatModel {

    private final StreamingChatModel delegate;
    private final CallAttribution attribution;
    private final ModelInfo modelInfo;
    private final UsageGate gate;

    public BudgetGatedStreamingChatModel(
            StreamingChatModel delegate, CallAttribution attribution, ModelInfo modelInfo, UsageGate gate) {
        this.delegate = delegate;
        this.attribution = attribution;
        this.modelInfo = modelInfo;
        this.gate = gate;
    }

    @Override
    public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
        try {
            gate.admit(attribution, modelInfo);
        } catch (LlmBudgetExceededException refused) {
            handler.onError(refused);
            return;
        }
        delegate.chat(request, handler);
    }
}
