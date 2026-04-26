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
 * <p>API key resolution per call uses the same
 * {@code ai.provider.<provider>.apiKey} setting the chat side uses,
 * so adding embedding capability to a tenant is just two settings:
 * {@code ai.embedding.provider} and {@code ai.embedding.model}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RagService {

    private static final String SETTINGS_REF_TYPE = "tenant";
    private static final String SETTING_EMBED_PROVIDER = "ai.embedding.provider";
    private static final String SETTING_EMBED_MODEL = "ai.embedding.model";
    private static final String SETTING_DEFAULT_PROVIDER = "ai.default.provider";
    private static final String SETTING_PROVIDER_API_KEY_FMT = "ai.provider.%s.apiKey";

    private static final String DEFAULT_EMBED_PROVIDER = "gemini";
    private static final String DEFAULT_EMBED_MODEL = "text-embedding-004";

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
        String apiKeySetting = String.format(
                SETTING_PROVIDER_API_KEY_FMT, rag.getEmbeddingProvider());
        String apiKey = settingService.getDecryptedPassword(
                rag.getTenantId(), SETTINGS_REF_TYPE, rag.getTenantId(), apiKeySetting);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "No API key for embedding provider '" + rag.getEmbeddingProvider()
                            + "' (tenant='" + rag.getTenantId()
                            + "', setting='" + apiKeySetting + "')");
        }
        return embeddingModelService.createEmbeddingModel(new EmbeddingConfig(
                rag.getEmbeddingProvider(), rag.getEmbeddingModel(), apiKey));
    }

    private EmbeddingConfig resolveEmbeddingConfig(String tenantId) {
        String provider = settingService.getStringValue(
                tenantId, SETTINGS_REF_TYPE, tenantId,
                SETTING_EMBED_PROVIDER, DEFAULT_EMBED_PROVIDER);
        String model = settingService.getStringValue(
                tenantId, SETTINGS_REF_TYPE, tenantId,
                SETTING_EMBED_MODEL, DEFAULT_EMBED_MODEL);
        // Fall back to chat provider if embedding provider isn't explicitly set.
        if (provider == null || provider.isBlank()) {
            provider = settingService.getStringValue(
                    tenantId, SETTINGS_REF_TYPE, tenantId,
                    SETTING_DEFAULT_PROVIDER, DEFAULT_EMBED_PROVIDER);
        }
        String apiKeySetting = String.format(SETTING_PROVIDER_API_KEY_FMT, provider);
        String apiKey = settingService.getDecryptedPassword(
                tenantId, SETTINGS_REF_TYPE, tenantId, apiKeySetting);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "No API key for embedding provider '" + provider
                            + "' (tenant='" + tenantId
                            + "', setting='" + apiKeySetting + "')");
        }
        return new EmbeddingConfig(provider, model, apiKey);
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
