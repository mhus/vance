package de.mhus.vance.brain.ai.embedding;

import de.mhus.vance.brain.ai.ModelInfo;
import de.mhus.vance.brain.ai.UsageGate;
import de.mhus.vance.shared.llmusage.CallAttribution;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.ContentType;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.listener.EmbeddingModelListener;
import dev.langchain4j.model.embedding.request.EmbeddingParameter;
import dev.langchain4j.model.embedding.request.EmbeddingRequest;
import dev.langchain4j.model.embedding.request.EmbeddingRequestParameters;
import dev.langchain4j.model.embedding.response.EmbeddingResponse;
import dev.langchain4j.model.output.Response;
import java.util.List;
import java.util.Set;

/**
 * Embedding counterpart of {@link de.mhus.vance.brain.ai.BudgetGatedChatModel}:
 * asks the {@link UsageGate} before every call and refuses it without touching
 * the wire when a budget forbids it.
 *
 * <p>Embedding cost is part of what a budget counts
 * ({@code LlmUsageService.costTotals} sums chat and embedding buckets), so it
 * has to pass the same gate — otherwise an exhausted budget would still let
 * RAG ingests spend. Every entry point that reaches the provider is gated
 * once: {@code embed(String)} goes through {@link #embed(TextSegment)}, the
 * others delegate directly. The model's metadata ({@link #modelName()},
 * {@link #provider()}, …) is not a call and passes through, and so does
 * {@link #dimension()}: most providers know it, and one that does not probes
 * with a single string — not worth refusing a caller that only asks for a
 * number. The RAG dimension probe itself goes through {@code embed} and is
 * gated.
 *
 * <p>Whether a call is refused is the gate's question, answered per call — a
 * model without pricing costs nothing the budget could protect and is
 * admitted (see {@code LlmBudgetGate}).
 *
 * <p>Attached in one place, {@link EmbeddingModelService#createEmbeddingModel(EmbeddingConfig, CallAttribution)}.
 */
public class BudgetGatedEmbeddingModel implements EmbeddingModel {

    private final EmbeddingModel delegate;
    private final CallAttribution attribution;
    private final ModelInfo modelInfo;
    private final UsageGate gate;

    public BudgetGatedEmbeddingModel(
            EmbeddingModel delegate, CallAttribution attribution, ModelInfo modelInfo, UsageGate gate) {
        this.delegate = delegate;
        this.attribution = attribution;
        this.modelInfo = modelInfo;
        this.gate = gate;
    }

    @Override
    public EmbeddingResponse embed(EmbeddingRequest request) {
        gate.admit(attribution, modelInfo);
        return delegate.embed(request);
    }

    @Override
    public EmbeddingResponse doEmbed(EmbeddingRequest request) {
        gate.admit(attribution, modelInfo);
        return delegate.doEmbed(request);
    }

    @Override
    public Response<Embedding> embed(TextSegment textSegment) {
        gate.admit(attribution, modelInfo);
        return delegate.embed(textSegment);
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
        gate.admit(attribution, modelInfo);
        return delegate.embedAll(textSegments);
    }

    @Override
    public int dimension() {
        return delegate.dimension();
    }

    @Override
    public String modelName() {
        return delegate.modelName();
    }

    @Override
    public ModelProvider provider() {
        return delegate.provider();
    }

    @Override
    public List<EmbeddingModelListener> listeners() {
        return delegate.listeners();
    }

    @Override
    public EmbeddingRequestParameters defaultRequestParameters() {
        return delegate.defaultRequestParameters();
    }

    @Override
    public Set<EmbeddingParameter<?>> supportedParameters() {
        return delegate.supportedParameters();
    }

    @Override
    public Set<ContentType> supportedContentTypes() {
        return delegate.supportedContentTypes();
    }
}
