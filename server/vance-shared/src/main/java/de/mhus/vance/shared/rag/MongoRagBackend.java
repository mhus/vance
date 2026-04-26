package de.mhus.vance.shared.rag;

import com.mongodb.client.result.DeleteResult;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Mongo-backed {@link RagBackend}. Chunks live in {@code rag_chunks};
 * search streams them and runs a brute-force cosine similarity in the
 * application thread, keeping a top-K min-heap to bound memory.
 *
 * <p>Streaming is deliberate: 50 000 chunks × 768 floats × 4 bytes ≈
 * 150 MB if you slurped everything at once. {@link MongoTemplate#stream}
 * iterates the cursor lazily, so memory stays at "one chunk + heap of K".
 *
 * <p>This is fine up to ~50 000 chunks per RAG on commodity hardware.
 * Beyond that, swap the bean for an Atlas-backed or Qdrant-backed
 * implementation behind the same interface — call sites don't change.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MongoRagBackend implements RagBackend {

    private static final int WARN_AT_CHUNKS = 10_000;
    private static final int CAP_AT_CHUNKS = 50_000;

    private final MongoTemplate mongoTemplate;

    @Override
    public void addChunks(List<RagChunkDocument> chunks) {
        if (chunks == null || chunks.isEmpty()) return;
        mongoTemplate.insertAll(chunks);
    }

    @Override
    public List<SearchHit> search(
            String tenantId, String ragId, float[] queryVector, int topK) {
        if (queryVector == null || queryVector.length == 0) {
            throw new IllegalArgumentException("queryVector is required");
        }
        int k = Math.max(1, topK);
        Query query = new Query(Criteria.where("tenantId").is(tenantId)
                .and("ragId").is(ragId));

        // Min-heap on score so the smallest-of-the-best is at the head and
        // can be evicted when something better comes along.
        PriorityQueue<SearchHit> top = new PriorityQueue<>(
                k, java.util.Comparator.comparingDouble(SearchHit::score));
        long seen = 0;
        try (Stream<RagChunkDocument> stream = mongoTemplate.stream(query, RagChunkDocument.class)) {
            for (RagChunkDocument chunk : (Iterable<RagChunkDocument>) stream::iterator) {
                seen++;
                double score = cosine(queryVector, chunk.getEmbedding());
                if (top.size() < k) {
                    top.add(SearchHit.of(chunk, score));
                } else if (score > top.peek().score()) {
                    top.poll();
                    top.add(SearchHit.of(chunk, score));
                }
            }
        }
        if (seen > WARN_AT_CHUNKS) {
            log.warn("RAG search scanned {} chunks (rag='{}') — getting close to the {}-chunk soft cap",
                    seen, ragId, CAP_AT_CHUNKS);
        }
        List<SearchHit> result = new ArrayList<>(top);
        result.sort((a, b) -> Double.compare(b.score(), a.score()));
        return result;
    }

    @Override
    public long count(String tenantId, String ragId) {
        Query query = new Query(Criteria.where("tenantId").is(tenantId)
                .and("ragId").is(ragId));
        return mongoTemplate.count(query, RagChunkDocument.class);
    }

    @Override
    public long deleteBySource(String tenantId, String ragId, String sourceRef) {
        Query query = new Query(Criteria.where("tenantId").is(tenantId)
                .and("ragId").is(ragId)
                .and("sourceRef").is(sourceRef));
        DeleteResult result = mongoTemplate.remove(query, RagChunkDocument.class);
        return result.getDeletedCount();
    }

    @Override
    public long deleteByRag(String tenantId, String ragId) {
        Query query = new Query(Criteria.where("tenantId").is(tenantId)
                .and("ragId").is(ragId));
        DeleteResult result = mongoTemplate.remove(query, RagChunkDocument.class);
        return result.getDeletedCount();
    }

    /** Cosine similarity between {@code q} and a {@code List<Float>} stored vector. */
    private static double cosine(float[] q, List<Float> v) {
        if (v == null || v.isEmpty() || v.size() != q.length) return 0.0;
        double dot = 0.0;
        double qNorm = 0.0;
        double vNorm = 0.0;
        for (int i = 0; i < q.length; i++) {
            double a = q[i];
            double b = v.get(i);
            dot += a * b;
            qNorm += a * a;
            vNorm += b * b;
        }
        double denom = Math.sqrt(qNorm) * Math.sqrt(vNorm);
        return denom == 0.0 ? 0.0 : dot / denom;
    }
}
