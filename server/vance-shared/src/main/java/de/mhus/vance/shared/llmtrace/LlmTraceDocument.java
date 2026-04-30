package de.mhus.vance.shared.llmtrace;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Persisted record of one LLM-roundtrip leg — input message, output
 * message, tool-call, or tool-result. One document per leg keeps the
 * collection trivially append-only and lets queries page through a
 * process's history without unmarshalling embedded sub-documents.
 *
 * <p>Persistence is opt-in: the {@code tracing.llm} setting
 * (cascade tenant → project → think-process) toggles whether engines
 * write to this collection. The {@code log.trace} channel runs
 * unconditionally regardless — this collection is the durable mirror
 * of that channel for tenants that want it.
 *
 * <p><b>Retention.</b> {@link #createdAt} is TTL-indexed at 90 days —
 * MongoDB drops old rows automatically. Adjust per-tenant retention is
 * a future concern.
 *
 * <p><b>Privacy.</b> Content is stored verbatim in v1. Tenants with PII
 * concerns should keep the toggle off until per-record encryption
 * lands.
 */
@Document(collection = "llm_traces")
@CompoundIndexes({
        @CompoundIndex(
                name = "tenant_process_createdAt_idx",
                def = "{ 'tenantId': 1, 'processId': 1, 'createdAt': 1 }"),
        @CompoundIndex(
                name = "tenant_session_createdAt_idx",
                def = "{ 'tenantId': 1, 'sessionId': 1, 'createdAt': 1 }")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmTraceDocument {

    @Id
    private @Nullable String id;

    /** Always set. */
    private String tenantId = "";

    /** Project the process runs in — may be {@code null} for tenant-scoped admin flows. */
    private @Nullable String projectId;

    /** Session the trace belongs to — may be {@code null} for non-session contexts (e.g. memory-compaction). */
    private @Nullable String sessionId;

    /** Owning think-process — primary lookup key for Insights. */
    private String processId = "";

    /**
     * Engine that produced the call ({@code arthur}, {@code ford},
     * {@code marvin}, …). Useful when a process has multi-engine
     * lineage (e.g. memory compaction firing under a worker process).
     */
    private @Nullable String engine;

    /**
     * Stable id for one engine round-trip. All legs of the same
     * round-trip — INPUT messages, OUTPUT, tool-calls, tool-results —
     * share this id so the UI can group them.
     */
    private @Nullable String turnId;

    /** Sequence within the round-trip — 0-based, defines the leg order. */
    private int sequence;

    /** Which leg of the round-trip this row represents. */
    private LlmTraceDirection direction;

    /**
     * Speaker role, when meaningful for {@link LlmTraceDirection#INPUT} /
     * {@link LlmTraceDirection#OUTPUT}: {@code system}, {@code user},
     * {@code assistant}, {@code tool}. Free-form string to stay flexible
     * across providers.
     */
    private @Nullable String role;

    /** Verbatim text content of the leg. May be very long — don't index. */
    private @Nullable String content;

    /** Tool-call name when {@link #direction} is {@code TOOL_CALL} or {@code TOOL_RESULT}. */
    private @Nullable String toolName;

    /** Provider-issued tool-call id, ties a TOOL_CALL leg to its TOOL_RESULT. */
    private @Nullable String toolCallId;

    /** Resolved model alias (e.g. {@code default:analyze}) when relevant. */
    private @Nullable String modelAlias;

    /** Concrete provider:model the call ran against. */
    private @Nullable String providerModel;

    /** Input tokens for this leg (if known — typically only on OUTPUT rows). */
    private @Nullable Integer tokensIn;

    /** Output tokens for this leg. */
    private @Nullable Integer tokensOut;

    /** Wall-clock the underlying LLM call took, in milliseconds. */
    private @Nullable Long elapsedMs;

    /**
     * Insertion timestamp. TTL-indexed: MongoDB drops the row 90 days
     * after this. (Spring's {@code expireAfter} on @Indexed accepts
     * an ISO-8601 duration string.)
     */
    @Indexed(expireAfter = "P90D")
    private Instant createdAt = Instant.now();
}
