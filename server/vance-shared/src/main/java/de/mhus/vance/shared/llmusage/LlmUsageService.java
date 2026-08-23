package de.mhus.vance.shared.llmusage;

import com.mongodb.MongoWriteException;
import de.mhus.vance.shared.settings.RetentionSettingCache;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Datenhoheit over the two usage levels. Callers never persist directly —
 * they hand a {@link UsageWrite} to {@link #record}, which
 *
 * <ol>
 *   <li>computes cost from the rate snapshot,
 *   <li>increments the day bucket ({@link LlmUsageDailyDocument} — the
 *       billing record), and
 *   <li>writes the per-attempt detail row ({@link LlmUsageDocument} —
 *       diagnostics, short-lived, optional).
 * </ol>
 *
 * <p><b>One method, two writes.</b> Deliberately not two callers: the day
 * bucket is what the report reads, and a code path that books the detail
 * but forgets the bucket would silently cost money on the invoice.
 *
 * <h2>Cost math</h2>
 *
 * {@code tokens / 1_000_000 × rate} for each of input/output/cacheRead/
 * cacheWrite, plus a flat per-image amount for {@link UsageKind#IMAGE}.
 * The components are persisted individually so reports can break them down
 * without joining back to the rate snapshot.
 *
 * <h2>Pricing coverage</h2>
 *
 * A model with no {@code pricing:} block produces a token-only row: real
 * counts, zero cost. That is honest per row but dishonest in a sum, so the
 * bucket also counts {@link LlmUsageDailyDocument#getUnpricedCalls()} and
 * the report states its coverage instead of presenting a total that looks
 * complete.
 *
 * <h2>Retention</h2>
 *
 * {@code expiresAt} is computed per write from the settings cascade and
 * reaped by Mongo's TTL monitor, like the Megadodo feed and the run logs.
 * The detail level is tri-state ({@code > 0} days, {@code 0} infinite,
 * {@code < 0} do not write); the day bucket is bi-state, because you do not
 * switch off billing with a number.
 *
 * <h2>Failure policy</h2>
 *
 * Never throws. A bookkeeping failure must not break the turn it observes;
 * the affected call is logged at WARN and stays unaccounted.
 */
@Service
@Slf4j
public class LlmUsageService {

    /**
     * Synthetic {@code caller} for calls that no think-engine issued — the
     * single-shot {@code LightLlmService} path (discovery, follow-up, title
     * generation, triage, …). Leading underscore follows the system-namespace
     * convention, so per-caller reports show this volume as its own row
     * instead of an unattributed gap.
     */
    public static final String CALLER_LIGHT = "_light";

    /** Image generation issued by Fenchurch outside any think-process. */
    public static final String CALLER_FENCHURCH = "_fenchurch";

    /** Embedding batches issued by the RAG service. */
    public static final String CALLER_RAG = "_rag";

    /** Upper clamp so a fat-fingered setting cannot mean "forever" by accident. */
    static final int MAX_RETENTION_DAYS = 3650;

    static final String SETTING_DAILY_RETENTION = "usage.retentionDays";
    static final String SETTING_DETAIL_RETENTION = "usage.detailRetentionDays";
    static final String SETTING_DETAIL_RETENTION_FAILED = "usage.detailRetentionDaysFailed";

    private final MongoTemplate mongoTemplate;
    private final RetentionSettingCache retentionCache;
    private final int defaultDailyRetentionDays;
    private final int defaultDetailRetentionDays;
    private final int defaultDetailRetentionDaysFailed;

    public LlmUsageService(
            MongoTemplate mongoTemplate,
            RetentionSettingCache retentionCache,
            @Value("${vance.usage.retention-days:0}") int defaultDailyRetentionDays,
            @Value("${vance.usage.detail-retention-days:60}") int defaultDetailRetentionDays,
            @Value("${vance.usage.detail-retention-days-failed:14}")
                    int defaultDetailRetentionDaysFailed) {
        this.mongoTemplate = mongoTemplate;
        this.retentionCache = retentionCache;
        this.defaultDailyRetentionDays = clamp(defaultDailyRetentionDays);
        this.defaultDetailRetentionDays = clamp(defaultDetailRetentionDays);
        this.defaultDetailRetentionDaysFailed = clamp(defaultDetailRetentionDaysFailed);
    }

    /**
     * Book one model-call attempt: increment the day bucket, then write the
     * detail row.
     *
     * <p>Bucket first on purpose. If the process dies between the two, the
     * invoice is right and only the drill-down is missing — the other order
     * would lose money and keep a trace of it.
     */
    public void record(UsageWrite write) {
        Costs costs = Costs.of(write);
        try {
            incrementDaily(write, costs);
        } catch (RuntimeException e) {
            log.warn("LlmUsage daily bucket failed tenant='{}' caller='{}': {}",
                    write.attribution().tenantId(), write.attribution().caller(), e.toString());
        }
        try {
            writeDetail(write, costs);
        } catch (RuntimeException e) {
            log.warn("LlmUsage detail row failed tenant='{}' process='{}': {}",
                    write.attribution().tenantId(), write.attribution().processId(), e.toString());
        }
    }

    // ═════════════════════════ Day bucket ═════════════════════════

    private void incrementDaily(UsageWrite w, Costs costs) {
        CallAttribution a = w.attribution();
        int retentionDays = retentionDays(a, SETTING_DAILY_RETENTION, defaultDailyRetentionDays);
        // Bi-state: the billing record cannot be switched off with a number.
        if (retentionDays < 0) {
            retentionDays = 0;
        }

        String day = LlmUsageDailyDocument.dayOf(w.createdAt());
        String projectId = LlmUsageDailyDocument.key(a.projectId());
        String caller = LlmUsageDailyDocument.key(a.caller());
        String recipeName = LlmUsageDailyDocument.key(a.recipeName());
        String providerModel = LlmUsageDailyDocument.key(w.providerModel());
        String currency = LlmUsageDailyDocument.key(w.currency());
        UsageKind kind = w.kind();
        String bucketId = LlmUsageDailyDocument.bucketId(
                a.tenantId(), day, projectId, caller, recipeName, providerModel, currency, kind);

        Update update = new Update()
                .setOnInsert("tenantId", a.tenantId())
                .setOnInsert("day", day)
                .setOnInsert("projectId", projectId)
                .setOnInsert("caller", caller)
                .setOnInsert("recipeName", recipeName)
                .setOnInsert("providerModel", providerModel)
                .setOnInsert("currency", currency)
                .setOnInsert("kind", kind)
                .setOnInsert("firstAt", w.createdAt())
                .set("lastAt", w.createdAt());

        // Expiry derives from the day, not from "now", and only on insert —
        // otherwise every increment would push it out and a busy day would
        // never age out.
        if (retentionDays > 0) {
            update.setOnInsert("expiresAt", LlmUsageDailyDocument.dayStart(day)
                    .plusSeconds(Duration.ofDays(retentionDays).toSeconds()));
        }

        // Amounts go in as integer micro-units. The $inc that lands here runs
        // once per attempt and is never recomputed, so it has to be exact and
        // order-independent — see the field comment on LlmUsageDailyDocument.
        if (w.outcome() == UsageOutcome.FAILED) {
            update.inc("callsFailed", 1L)
                    .inc("tokensInFailed", w.tokensIn())
                    .inc("tokensOutFailed", w.tokensOut())
                    .inc("costFailedMicros", toMicros(costs.total()));
        } else {
            update.inc("calls", 1L)
                    .inc("tokensIn", w.tokensIn())
                    .inc("tokensOut", w.tokensOut())
                    .inc("cacheReadTokens", w.cacheReadTokens())
                    .inc("cacheWriteTokens", w.cacheWriteTokens())
                    .inc("images", w.images())
                    .inc("costInputMicros", toMicros(costs.input()))
                    .inc("costOutputMicros", toMicros(costs.output()))
                    .inc("costCacheReadMicros", toMicros(costs.cacheRead()))
                    .inc("costCacheWriteMicros", toMicros(costs.cacheWrite()))
                    // Summed from the rounded parts, not rounded from the sum:
                    // otherwise costTotal and the four components disagree by a
                    // micro-unit now and then, and a report that shows both
                    // would be visibly inconsistent.
                    .inc("costTotalMicros",
                            toMicros(costs.input()) + toMicros(costs.output())
                                    + toMicros(costs.cacheRead())
                                    + toMicros(costs.cacheWrite()));
            if (w.unmeasured()) {
                update.inc("unmeasuredCalls", 1L);
            } else if (!w.priced()) {
                update.inc("unpricedCalls", 1L)
                        .inc("unpricedTokensIn", w.tokensIn())
                        .inc("unpricedTokensOut", w.tokensOut());
            }
        }

        Query byId = Query.query(Criteria.where("_id").is(bucketId));
        try {
            mongoTemplate.upsert(byId, update, LlmUsageDailyDocument.class);
        } catch (RuntimeException e) {
            // Two writers inserted the same bucket in the same instant. The
            // document exists now, so the retry takes the $inc branch. Any
            // other failure is not ours to swallow here.
            if (!isDuplicateKey(e)) {
                throw e;
            }
            log.trace("LlmUsage daily bucket insert raced, retrying: {}", bucketId);
            mongoTemplate.upsert(byId, update, LlmUsageDailyDocument.class);
        }
    }

    /**
     * An amount in {@code currency} as integer micro-units, rounded half-up.
     *
     * <p>The unit of the day bucket. A micro-unit is 1e-6, finer than any
     * per-token price the catalog carries, so the rounding error per attempt is
     * below the resolution anyone bills at — and unlike a {@code double} it
     * does not grow with the number of attempts, which is the whole point.
     *
     * <p>{@code NaN} and infinities become {@code 0}: a nonsensical rate in a
     * catalog entry must not be able to poison a tenant's day total with a
     * value no later write can correct.
     */
    public static long toMicros(double amount) {
        if (Double.isNaN(amount) || Double.isInfinite(amount)) {
            return 0L;
        }
        return Math.round(amount * 1_000_000.0d);
    }

    /** The inverse of {@link #toMicros}, for display and for the wire. */
    public static double fromMicros(long micros) {
        return micros / 1_000_000.0d;
    }

    /**
     * Duplicate-key detection across both shapes: Spring translates most
     * Mongo write errors into {@link DuplicateKeyException}, but the raw
     * driver exception reaches us when translation is not in play (plain
     * {@code MongoTemplate} without a configured translator, and in tests).
     */
    private static boolean isDuplicateKey(RuntimeException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof DuplicateKeyException) return true;
            if (t instanceof MongoWriteException mwe && mwe.getError().getCode() == 11000) {
                return true;
            }
            if (t.getCause() == t) break;
        }
        return false;
    }

    // ═════════════════════════ Detail row ═════════════════════════

    private void writeDetail(UsageWrite w, Costs costs) {
        CallAttribution a = w.attribution();
        String setting = w.outcome() == UsageOutcome.FAILED
                ? SETTING_DETAIL_RETENTION_FAILED
                : SETTING_DETAIL_RETENTION;
        int fallback = w.outcome() == UsageOutcome.FAILED
                ? defaultDetailRetentionDaysFailed
                : defaultDetailRetentionDays;
        int retentionDays = retentionDays(a, setting, fallback);
        // Tri-state: the detail level may be switched off. It is diagnostics,
        // and the day bucket already carries the money.
        if (retentionDays < 0) {
            return;
        }

        LlmUsageDocument doc = build(w, costs);
        if (retentionDays > 0) {
            doc.setExpiresAt(w.createdAt()
                    .plusSeconds(Duration.ofDays(retentionDays).toSeconds()));
        }
        mongoTemplate.insert(doc);
    }

    /** Visible-for-testing — pure transform, no I/O. */
    static LlmUsageDocument build(UsageWrite w, Costs costs) {
        CallAttribution a = w.attribution();
        return LlmUsageDocument.builder()
                .tenantId(a.tenantId())
                .projectId(blankToNull(a.projectId()))
                .sessionId(blankToNull(a.sessionId()))
                .processId(a.processId() == null ? "" : a.processId())
                .recipeName(blankToNull(a.recipeName()))
                .engineName(a.caller())
                .providerInstance(blankToNull(w.providerInstance()))
                .providerType(blankToNull(w.providerType()))
                .providerModel(blankToNull(w.providerModel()))
                .modelAlias(blankToNull(w.modelAlias()))
                .tokensIn(w.tokensIn())
                .tokensOut(w.tokensOut())
                .cacheReadTokens(w.cacheReadTokens())
                .cacheWriteTokens(w.cacheWriteTokens())
                .priceInputPerMTok(w.priceInputPerMTok())
                .priceOutputPerMTok(w.priceOutputPerMTok())
                .priceCacheReadPerMTok(w.priceCacheReadPerMTok())
                .priceCacheWritePerMTok(w.priceCacheWritePerMTok())
                .currency(blankToNull(w.currency()))
                .costInput(costs.input())
                .costOutput(costs.output())
                .costCacheRead(costs.cacheRead())
                .costCacheWrite(costs.cacheWrite())
                .costTotal(costs.total())
                .durationMs(w.durationMs())
                .contextWindowTokens(w.contextWindowTokens())
                .kind(w.kind())
                .outcome(w.outcome())
                .attempt(w.attempt())
                .images(w.images())
                .createdAt(w.createdAt())
                .build();
    }

    /** Visible-for-testing — same transform with costs derived from {@code w}. */
    static LlmUsageDocument build(UsageWrite w) {
        return build(w, Costs.of(w));
    }

    // ═════════════════════════ Helpers ═════════════════════════

    /**
     * Effective retention for this attribution — project beats tenant beats
     * {@code application.yml}. Tri-state is preserved here; the callers
     * decide whether they honour {@code < 0}.
     */
    private int retentionDays(CallAttribution a, String settingKey, int fallback) {
        int days = fallback;
        try {
            // Through the cache: this runs twice per model-call attempt, and
            // the settings cascade is up to three uncached Mongo reads. A chat
            // turn with one retry used to pay a dozen of them for two numbers
            // that change approximately never. See RetentionSettingCache.
            days = retentionCache.days(a.tenantId(), a.projectId(), settingKey, fallback);
        } catch (RuntimeException e) {
            log.debug("LlmUsage — retention lookup failed for '{}': {}", settingKey, e.toString());
        }
        if (days <= 0) return days;
        return Math.min(MAX_RETENTION_DAYS, days);
    }

    private static int clamp(int days) {
        if (days <= 0) return days;
        return Math.min(MAX_RETENTION_DAYS, days);
    }

    private static @Nullable String blankToNull(@Nullable String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** The four cost components plus their sum. */
    record Costs(double input, double output, double cacheRead, double cacheWrite) {

        static Costs of(UsageWrite w) {
            if (w.kind() == UsageKind.IMAGE) {
                // Images are priced per generated image, not per token. The
                // amount lands in the input bucket so `costTotal` stays the
                // one number every report sums.
                double amount = w.imageCost() == null ? 0.0 : w.imageCost();
                return new Costs(amount, 0.0, 0.0, 0.0);
            }
            return new Costs(
                    costOf(w.tokensIn(), w.priceInputPerMTok()),
                    costOf(w.tokensOut(), w.priceOutputPerMTok()),
                    costOf(w.cacheReadTokens(), w.priceCacheReadPerMTok()),
                    costOf(w.cacheWriteTokens(), w.priceCacheWritePerMTok()));
        }

        double total() {
            return input + output + cacheRead + cacheWrite;
        }

        /** Cost helper: {@code tokens / 1_000_000 × rate}. Null rate ⇒ 0. */
        private static double costOf(int tokens, @Nullable Double ratePerMTok) {
            if (tokens <= 0 || ratePerMTok == null || ratePerMTok <= 0.0) {
                return 0.0;
            }
            return tokens / 1_000_000.0 * ratePerMTok;
        }
    }

    /**
     * Input bundle for {@link #record(UsageWrite)}. Identity travels as one
     * {@link CallAttribution} rather than six loose fields — unpacking it at
     * the boundary is how the two former writers drifted apart.
     */
    @lombok.Builder
    public record UsageWrite(
            CallAttribution attribution,
            UsageKind kind,
            UsageOutcome outcome,
            /**
             * The attempt happened but the provider reported no token counts
             * at all — booked as a call with zero tokens and zero cost, and
             * counted separately so the report can say so. Distinct from
             * {@link #priced()}: unpriced means "we know the tokens, not the
             * rate", unmeasured means "we do not even know the tokens".
             */
            boolean unmeasured,
            /** 1-based; values above 1 are retries or fallback-chain advances. */
            int attempt,
            @Nullable String providerInstance,
            @Nullable String providerType,
            @Nullable String providerModel,
            @Nullable String modelAlias,
            int tokensIn,
            int tokensOut,
            int cacheReadTokens,
            int cacheWriteTokens,
            /** Generated images; {@link UsageKind#IMAGE} only. */
            int images,
            /** Flat amount for an image call; {@link UsageKind#IMAGE} only. */
            @Nullable Double imageCost,
            @Nullable Double priceInputPerMTok,
            @Nullable Double priceOutputPerMTok,
            @Nullable Double priceCacheReadPerMTok,
            @Nullable Double priceCacheWritePerMTok,
            @Nullable String currency,
            long durationMs,
            @Nullable Integer contextWindowTokens,
            Instant createdAt) {

        public UsageWrite {
            if (attribution == null) {
                throw new IllegalArgumentException("UsageWrite.attribution is required");
            }
            if (kind == null) kind = UsageKind.CHAT;
            if (outcome == null) outcome = UsageOutcome.SUCCESS;
            if (attempt <= 0) attempt = 1;
            if (createdAt == null) createdAt = Instant.now();
        }

        /**
         * Whether this call had a price at all. A model without a
         * {@code pricing:} block is not free — it is unknown, and the report
         * has to say so rather than adding a zero into the total.
         *
         * <p>Local models are made explicit in the catalog with a zero rate,
         * so "no block" means "price missing" and nothing else.
         */
        public boolean priced() {
            if (kind == UsageKind.IMAGE) {
                return imageCost != null;
            }
            return priceInputPerMTok != null || priceOutputPerMTok != null;
        }
    }
}
