package de.mhus.vance.shared.llmusage;

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
 * One persisted record per model-call <i>attempt</i> — the detail level.
 *
 * <p><b>Not the billing record.</b> That is
 * {@link LlmUsageDailyDocument}, which is incremented in the same write.
 * These rows exist for drill-down: which session, which process, which
 * attempt, what did the retry cost. Because that is diagnostics rather
 * than money, they carry a short {@link #expiresAt} and may be switched
 * off entirely per tenant — something the ledger could not allow while
 * it *was* the billing record.
 *
 * <p>Carries three layers per call:
 *
 * <ol>
 *   <li><b>Token counts</b> — what the provider reported as input,
 *       output, cache-read and cache-write tokens.
 *   <li><b>Rate snapshot</b> — the per-million-token prices from
 *       {@code ai-models.yaml} at the moment of the call. Verewigt so
 *       later YAML edits do not rewrite history.
 *   <li><b>Computed cost</b> — {@code tokens / 1_000_000 * rate} for
 *       each bucket plus the total. Stored to keep aggregation queries
 *       trivial ({@code $sum: "$costTotal"}) and to honour the
 *       audit-trail contract: the report value is exactly the value
 *       that was written, never re-derived from the current YAML.
 * </ol>
 *
 * <p>{@code currency} is per-row because different providers bill in
 * different currencies (Cortecs EUR, Anthropic USD, …). Cross-currency
 * reports either filter by currency or normalise via a tenant-level FX
 * setting.
 */
@Document(collection = "llm_usage_records")
@CompoundIndexes({
        @CompoundIndex(
                name = "tenant_createdAt_idx",
                def = "{ 'tenantId': 1, 'createdAt': 1 }"),
        @CompoundIndex(
                name = "tenant_project_createdAt_idx",
                def = "{ 'tenantId': 1, 'projectId': 1, 'createdAt': 1 }"),
        @CompoundIndex(
                name = "tenant_providerModel_createdAt_idx",
                def = "{ 'tenantId': 1, 'providerModel': 1, 'createdAt': 1 }")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmUsageDocument {

    @Id
    private @Nullable String id;

    /** Always set. */
    private String tenantId = "";

    /** Project the process runs in — may be {@code null} for tenant-scoped admin flows. */
    private @Nullable String projectId;

    /** Session the call belongs to — may be {@code null} for non-session contexts. */
    private @Nullable String sessionId;

    /** Think-process that issued the call. */
    private String processId = "";

    /** Recipe in effect at the time of the call (informational, for reports). */
    private @Nullable String recipeName;

    /** Engine in effect at the time of the call (informational, for reports). */
    private @Nullable String engineName;

    /**
     * Provider instance label — e.g. {@code openai}, {@code cortecs},
     * {@code gemini}. May differ from {@link #providerType} when a
     * tenant declares a custom instance on a known wire protocol.
     */
    private @Nullable String providerInstance;

    /**
     * Wire-protocol type — what underlying SDK was used. Same value
     * the alias cascade resolves to. Lets reports group by provider
     * regardless of instance naming.
     */
    private @Nullable String providerType;

    /** Concrete model name as the provider reported it. */
    private @Nullable String providerModel;

    /** Alias the engine asked for (e.g. {@code default:fast}) — informational. */
    private @Nullable String modelAlias;

    // ── Token counts ──────────────────────────────────────────────
    private int tokensIn;
    private int tokensOut;
    private int cacheReadTokens;
    private int cacheWriteTokens;

    // ── Rate snapshot (per million tokens, currency below) ────────
    private @Nullable Double priceInputPerMTok;
    private @Nullable Double priceOutputPerMTok;
    private @Nullable Double priceCacheReadPerMTok;
    private @Nullable Double priceCacheWritePerMTok;
    private @Nullable String currency;

    // ── Computed costs (rate × tokens / 1_000_000) ────────────────
    private double costInput;
    private double costOutput;
    private double costCacheRead;
    private double costCacheWrite;
    private double costTotal;

    // ── Misc ──────────────────────────────────────────────────────
    /** Wallclock latency of the round-trip, milliseconds. */
    private long durationMs;

    /** Context-window size of the model used; informational. */
    private @Nullable Integer contextWindowTokens;

    /**
     * Chat / embedding / image. {@code null} on rows written before the
     * ledger became kind-aware — read those as {@link UsageKind#CHAT}.
     */
    private @Nullable UsageKind kind;

    /**
     * Whether the attempt produced an answer. {@code null} on rows written
     * before failed attempts were recorded at all — read those as
     * {@link UsageOutcome#SUCCESS}, which is what they were.
     */
    private @Nullable UsageOutcome outcome;

    /**
     * 1-based attempt number within one logical call. Values above 1 are
     * retries inside {@code ResilientChatModel} or advances along a fallback
     * chain — each one burned tokens and gets its own row.
     */
    private int attempt;

    /** Number of images generated; only meaningful for {@link UsageKind#IMAGE}. */
    private int images;

    /**
     * TTL anchor, computed at write from the setting cascade.
     * {@code null} = keep forever. Detail rows are diagnostics, so unlike
     * the daily buckets they default to a short life.
     */
    @Indexed(name = "usage_ttl_idx", expireAfterSeconds = 0)
    private @Nullable Instant expiresAt;

    private Instant createdAt = Instant.EPOCH;
}
