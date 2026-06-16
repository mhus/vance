package de.mhus.vance.brain.ai.embedding;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.e5smallv2.E5SmallV2EmbeddingModel;
import org.springframework.stereotype.Component;

/**
 * In-process embedding via langchain4j's bundled E5-small-v2 ONNX
 * model — 384-dim, 512-token max input. The model weights ship inside
 * the {@code langchain4j-embeddings-e5-small-v2} jar; no API key, no
 * external service, no network call. Footprint: ~120 MB JAR plus
 * runtime memory for the loaded session.
 *
 * <p>Quality: E5-small-v2 is primarily trained on English data but
 * generalises moderately to other languages. For workloads that need
 * stronger multilingual retrieval, point the {@code openai} provider
 * at a self-hosted endpoint (Ollama {@code nomic-embed-text}, TEI with
 * BGE-M3) via {@code ai.embedding.baseUrl} instead.
 *
 * <p>The model instance is built once at bean-creation time and reused
 * for every call: the ONNX session and tokenizer are stateless across
 * invocations, and re-initialising them per request would burn ~1 sec
 * of CPU on each embed call.
 */
@Component
public class EmbeddedEmbeddingProvider implements EmbeddingProvider {

    public static final String NAME = "embedded";

    private final EmbeddingModel sharedModel = new E5SmallV2EmbeddingModel();

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean requiresApiKey() {
        return false;
    }

    @Override
    public EmbeddingModel createEmbeddingModel(EmbeddingConfig config) {
        return sharedModel;
    }
}
