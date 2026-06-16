package de.mhus.vance.brain.rag;

import de.mhus.vance.brain.ai.embedding.EmbeddingConfig;
import de.mhus.vance.brain.ai.embedding.EmbeddingModelService;
import de.mhus.vance.shared.rag.RagBackend;
import de.mhus.vance.shared.rag.RagBackend.SearchHit;
import de.mhus.vance.shared.rag.RagCatalogService;
import de.mhus.vance.shared.rag.RagChunkDocument;
import de.mhus.vance.shared.rag.RagDocument;
import de.mhus.vance.shared.settings.SettingService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Orchestrates RAG operations: catalog management,
 * chunk-and-embed-on-ingest, embed-and-search-on-query.
 *
 * <p>The embedding model used for a RAG is fixed at creation time
 * (stored on {@link RagDocument}) — changing the tenant default
 * later does not silently re-embed. Switching models would require
 * a re-ingest path, which we'll add when someone actually wants it.
 *
 * <p>Embedding settings are independent of the chat-LLM credential
 * namespace ({@code ai.provider.*}). A tenant configures embeddings
 * with up to four settings, all cascading tenant→project:
 * <ul>
 *   <li>{@code ai.embedding.provider} — provider id (default
 *       {@code gemini}; available: {@code gemini}, {@code openai}).</li>
 *   <li>{@code ai.embedding.model} — model name (default
 *       {@code gemini-embedding-001}).</li>
 *   <li>{@code ai.embedding.apiKey} — credential (PASSWORD-typed).</li>
 *   <li>{@code ai.embedding.baseUrl} — optional, for OpenAI-compatible
 *       endpoints (Ollama, TEI, custom gateway).</li>
 * </ul>
 * The separation lets a tenant use Cortecs for chat and Gemini /
 * OpenAI / a self-hosted compat endpoint for embeddings independently.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RagService {

    private static final String SETTING_EMBED_PROVIDER = "ai.embedding.provider";
    private static final String SETTING_EMBED_MODEL = "ai.embedding.model";
    private static final String SETTING_EMBED_API_KEY = "ai.embedding.apiKey";
    private static final String SETTING_EMBED_BASE_URL = "ai.embedding.baseUrl";

    private static final String DEFAULT_EMBED_PROVIDER = "gemini";
    // text-embedding-004 was deprecated on Google's v1beta endpoint
    // ("models/text-embedding-004 is not found … for embedContent");
    // gemini-embedding-001 is the GA successor. Tenants that want a
    // different default can set ai.embedding.model in settings.
    private static final String DEFAULT_EMBED_MODEL = "gemini-embedding-001";

    private final RagCatalogService catalog;
    private final RagBackend backend;
    private final EmbeddingModelService embeddingModelService;
    private final SettingService settingService;

    // ──────────────────── Catalog ────────────────────

    /**
     * Creates a new RAG and probes the embedding model to record its
     * vector dimension. The probe is a cheap one-string embed; it's
     * the simplest way to stay model-agnostic.
     */
    public RagDocument createRag(
            String tenantId,
            String projectId,
            String name,
            @Nullable String title,
            @Nullable String description,
            int chunkSize,
            int chunkOverlap) {
        EmbeddingConfig config = resolveEmbeddingConfig(tenantId);
        EmbeddingModel model = embeddingModelService.createEmbeddingModel(config);
        int dim = probeDimension(model);
        return catalog.create(
                tenantId, projectId, name, title, description,
                config.provider(), config.modelName(), dim,
                chunkSize, chunkOverlap);
    }

    public Optional<RagDocument> findByName(String tenantId, String projectId, String name) {
        return catalog.findByName(tenantId, projectId, name);
    }

    public List<RagDocument> listByProject(String tenantId, String projectId) {
        return catalog.listByProject(tenantId, projectId);
    }

    public boolean deleteRag(String ragId) {
        return catalog.delete(ragId);
    }

    // ──────────────────── Ingest ────────────────────

    /**
     * Chunk + embed + store {@code text} under {@code sourceRef}. If
     * a {@code sourceRef} already exists, callers should
     * {@link #removeBySource} first to avoid duplication.
     */
    public IngestResult addText(
            String ragId, @Nullable String sourceRef, String text,
            @Nullable Map<String, Object> metadata) {
        if (text == null || text.isBlank()) {
            return new IngestResult(0);
        }
        RagDocument rag = catalog.findById(ragId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "RAG not found: " + ragId));
        EmbeddingModel model = modelFor(rag);

        List<String> pieces = chunk(text, rag.getChunkSize(), rag.getChunkOverlap());
        if (pieces.isEmpty()) {
            return new IngestResult(0);
        }
        List<TextSegment> segments = pieces.stream().map(TextSegment::from).toList();
        Response<List<Embedding>> response = model.embedAll(segments);
        List<Embedding> embeddings = response.content();
        if (embeddings.size() != pieces.size()) {
            throw new IllegalStateException(
                    "Embedding count mismatch: expected " + pieces.size()
                            + " got " + embeddings.size());
        }

        Map<String, Object> baseMeta = metadata == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
        List<RagChunkDocument> docs = new ArrayList<>(pieces.size());
        for (int i = 0; i < pieces.size(); i++) {
            docs.add(RagChunkDocument.builder()
                    .tenantId(rag.getTenantId())
                    .projectId(rag.getProjectId())
                    .ragId(rag.getId())
                    .sourceRef(sourceRef)
                    .position(i)
                    .content(pieces.get(i))
                    .embedding(asList(embeddings.get(i).vector()))
                    .metadata(new LinkedHashMap<>(baseMeta))
                    .build());
        }
        backend.addChunks(docs);
        catalog.refreshChunkCount(rag);
        log.info("RAG ingest tenant='{}' rag='{}' source='{}' chunks={}",
                rag.getTenantId(), rag.getName(), sourceRef, docs.size());
        return new IngestResult(docs.size());
    }

    public long removeBySource(String ragId, String sourceRef) {
        RagDocument rag = catalog.findById(ragId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "RAG not found: " + ragId));
        long n = backend.deleteBySource(rag.getTenantId(), rag.getId(), sourceRef);
        catalog.refreshChunkCount(rag);
        return n;
    }

    // ──────────────────── Query ────────────────────

    public List<SearchHit> query(String ragId, String queryText, int topK) {
        RagDocument rag = catalog.findById(ragId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "RAG not found: " + ragId));
        if (queryText == null || queryText.isBlank()) {
            return List.of();
        }
        EmbeddingModel model = modelFor(rag);
        Embedding queryVec = model.embed(queryText).content();
        return backend.search(rag.getTenantId(), rag.getId(), queryVec.vector(), topK);
    }

    // ──────────────────── Helpers ────────────────────

    private EmbeddingModel modelFor(RagDocument rag) {
        // RAG-level operation has no process scope — read from the
        // _vance/project layer of the project cascade.
        String apiKey = settingService.getDecryptedPasswordCascade(
                rag.getTenantId(), /*projectId*/ null, /*processId*/ null, SETTING_EMBED_API_KEY);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "No API key for embedding provider '" + rag.getEmbeddingProvider()
                            + "' (tenant='" + rag.getTenantId()
                            + "', setting='" + SETTING_EMBED_API_KEY + "')");
        }
        String baseUrl = settingService.getStringValueCascade(
                rag.getTenantId(), /*projectId*/ null, /*processId*/ null, SETTING_EMBED_BASE_URL);
        return embeddingModelService.createEmbeddingModel(new EmbeddingConfig(
                rag.getEmbeddingProvider(), rag.getEmbeddingModel(), apiKey,
                StringUtils.isBlank(baseUrl) ? null : baseUrl));
    }

    private EmbeddingConfig resolveEmbeddingConfig(String tenantId) {
        String provider = settingService.getStringValueCascade(
                tenantId, /*projectId*/ null, /*processId*/ null, SETTING_EMBED_PROVIDER);
        if (provider == null || provider.isBlank()) provider = DEFAULT_EMBED_PROVIDER;
        String model = settingService.getStringValueCascade(
                tenantId, /*projectId*/ null, /*processId*/ null, SETTING_EMBED_MODEL);
        if (model == null || model.isBlank()) model = DEFAULT_EMBED_MODEL;
        String apiKey = settingService.getDecryptedPasswordCascade(
                tenantId, /*projectId*/ null, /*processId*/ null, SETTING_EMBED_API_KEY);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "No API key for embedding provider '" + provider
                            + "' (tenant='" + tenantId
                            + "', setting='" + SETTING_EMBED_API_KEY + "')");
        }
        String baseUrl = settingService.getStringValueCascade(
                tenantId, /*projectId*/ null, /*processId*/ null, SETTING_EMBED_BASE_URL);
        return new EmbeddingConfig(provider, model, apiKey,
                StringUtils.isBlank(baseUrl) ? null : baseUrl);
    }

    private static int probeDimension(EmbeddingModel model) {
        Embedding probe = model.embed("dim probe").content();
        return probe.dimension();
    }

    /** Char-based chunking with overlap. Handles short text and edge sizes. */
    static List<String> chunk(String text, int size, int overlap) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        int chunkSize = Math.max(1, size);
        int step = Math.max(1, chunkSize - Math.max(0, overlap));
        for (int i = 0; i < text.length(); i += step) {
            int end = Math.min(text.length(), i + chunkSize);
            out.add(text.substring(i, end));
            if (end >= text.length()) break;
        }
        return out;
    }

    private static List<Float> asList(float[] vec) {
        List<Float> out = new ArrayList<>(vec.length);
        for (float v : vec) out.add(v);
        return out;
    }

    /** Outcome of an {@link #addText} call. */
    public record IngestResult(int chunksAdded) {}
}
