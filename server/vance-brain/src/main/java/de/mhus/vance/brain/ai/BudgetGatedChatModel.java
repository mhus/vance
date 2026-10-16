package de.mhus.vance.brain.ai;

import de.mhus.vance.shared.llmusage.CallAttribution;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * Asks the {@link UsageGate} before every synchronous attempt and refuses it
 * without touching the wire when a budget is exhausted.
 *
 * <p>Sits directly outside {@link UsageAccountingChatModel}: a refused call
 * never reached the provider, so it books nothing — neither a success nor a
 * failed attempt, both of which would claim money was (maybe) spent. Inside
 * the retry layer, so each retry and each fallback entry asks again with its
 * own model; a fallback onto a free model is admitted.
 *
 * <p>Attached in one place only, {@link AbstractChatProvider#createChat},
 * next to the accounting decorator — for the same reason: a call site that
 * forgets cannot exist.
 */
public class BudgetGatedChatModel implements ChatModel {

    private final ChatModel delegate;
    private final CallAttribution attribution;
    private final ModelInfo modelInfo;
    private final UsageGate gate;

    public BudgetGatedChatModel(ChatModel delegate, CallAttribution attribution, ModelInfo modelInfo, UsageGate gate) {
        this.delegate = delegate;
        this.attribution = attribution;
        this.modelInfo = modelInfo;
        this.gate = gate;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        gate.admit(attribution, modelInfo);
        return delegate.chat(request);
    }
}
