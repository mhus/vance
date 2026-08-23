package de.mhus.vance.brain.tools.budget;

import de.mhus.vance.brain.ai.ToolLimitError;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Learns the real {@code tools}-array limit of an endpoint from its own
 * rejection, so a stale or missing catalog value corrects itself instead
 * of failing every turn.
 *
 * <p>Why this exists: the cap is a property of the endpoint, not of the
 * model. A gateway can enforce a limit the model's catalog entry doesn't
 * mention (or mentions wrongly), and no listing API exposes the number.
 * When a request comes back with "Invalid 'tools': array too long …
 * maximum length 128", that message <em>is</em> the missing metadata.
 *
 * <p>Keyed on {@code providerInstance:modelName} — the same label
 * {@code AiChatConfig.fullName()} produces, so the learner (AI layer) and
 * the consumer ({@link ToolBudgetService}) agree without a shared lookup.
 *
 * <p>In-memory and per-pod on purpose: it is a self-healing hint, not
 * configuration. The durable fix is {@code maxTools:} in the model
 * document, and the WARN this class logs says exactly that.
 */
@Component
public class ObservedToolLimitRegistry {

    private static final Logger log = LoggerFactory.getLogger(ObservedToolLimitRegistry.class);

    /** Sentinel for "no limit was known before this call" — every real limit is positive. */
    private static final int NONE = -1;

    /**
     * Smallest number this class will believe is a tools-array cap.
     *
     * <p>The mandatory floor ({@code tool_list} + {@code tool_description})
     * plus the engine's own action tool already needs three slots, so a
     * surface below that cannot be built at all. Adopting a smaller number
     * would not degrade the turn, it would <b>end</b> it: {@code ToolTriage}
     * throws {@code ToolBudgetException} when the limit cannot hold the
     * floor, and this registry has no TTL — every following turn of that
     * model on this pod would fail the same way until a restart.
     *
     * <p>Such a number is not hypothetical: {@code parseLimit} takes the
     * first {@code maximum length N} in a folded cause chain, and a
     * multi-error body can put a different field's limit in front of the
     * one about {@code tools}.
     */
    private static final int MIN_PLAUSIBLE_LIMIT = 4;

    private final Map<String, Integer> observed = new ConcurrentHashMap<>();

    /**
     * Bumped on every learned change. {@link ToolBudgetService} keys its
     * memo on this so a newly learned limit takes effect on the next
     * turn instead of waiting out a cache TTL.
     */
    private final AtomicLong version = new AtomicLong();

    /**
     * Record what the endpoint said. Keeps the smallest value ever seen
     * for the label — a cap can be lowered by a gateway change, and the
     * conservative value is the safe one.
     *
     * @param modelLabel   {@code providerInstance:modelName}
     * @param errorText    the provider's error message / body
     * @param requestedCount how many tool schemas the failed request carried
     * @return the learned limit, or empty when the message carried no number
     */
    public OptionalInt learnFrom(String modelLabel, String errorText, int requestedCount) {
        if (modelLabel == null || modelLabel.isBlank()) return OptionalInt.empty();
        OptionalInt parsed = ToolLimitError.parseLimit(errorText);
        if (parsed.isEmpty()) {
            log.error("Tool-surface: endpoint '{}' rejected {} tool schemas as too many, but the "
                            + "message carries no limit — set 'maxTools:' in the model document "
                            + "(or the provider's _provider.yaml) to fix this permanently. Message: {}",
                    modelLabel, requestedCount, abbreviate(errorText));
            return OptionalInt.empty();
        }
        int limit = parsed.getAsInt();
        if (!plausible(limit, requestedCount, modelLabel, errorText)) {
            return OptionalInt.empty();
        }
        String key = normalize(modelLabel);
        // Atomic min. Two turns can be rejected concurrently by the same
        // endpoint, and a get-then-put would let the larger of the two land
        // last — breaking the "smallest ever seen" invariant this registry
        // rests on. The prior value is captured inside the same atomic step
        // so "did we actually learn something" stays decidable: re-learning
        // a known limit must not bump the version (that would invalidate
        // ToolBudgetService's memo) nor repeat the WARN.
        AtomicInteger prior = new AtomicInteger(NONE);
        int effective = observed.compute(key, (k, known) -> {
            prior.set(known == null ? NONE : known);
            return known == null ? limit : Math.min(known, limit);
        });
        if (prior.get() != NONE && effective == prior.get()) {
            // Already knew an equal or stricter limit — nothing to learn.
            return OptionalInt.of(effective);
        }
        version.incrementAndGet();
        log.warn("Tool-surface: endpoint '{}' enforces maxTools={} but received {} schemas — "
                        + "learned the limit for this pod; the durable fix is 'maxTools: {}' in "
                        + "the model document (or the provider's _provider.yaml)",
                modelLabel, limit, requestedCount, limit);
        return OptionalInt.of(limit);
    }

    /** Learned limit for {@code providerInstance:modelName}, if any. */
    public OptionalInt observedFor(String modelLabel) {
        if (modelLabel == null || modelLabel.isBlank()) return OptionalInt.empty();
        Integer v = observed.get(normalize(modelLabel));
        return v == null ? OptionalInt.empty() : OptionalInt.of(v);
    }

    /** Monotonic counter — changes whenever a limit was learned. */
    public long version() {
        return version.get();
    }

    /**
     * Forget everything learned so far.
     *
     * <p>Reachable from the outside on purpose
     * ({@code POST /brain/{tenant}/admin/ai-models/forget-tool-limits}):
     * a learned value is in-memory, has no TTL and is deliberately
     * "smallest ever seen", so a once-mislearned cap would otherwise stand
     * until the pod restarts. Bumps the version, so the next turn resolves
     * from the catalog again instead of a stale memo.
     *
     * @return how many endpoint entries were dropped
     */
    public int clear() {
        int size = observed.size();
        observed.clear();
        version.incrementAndGet();
        return size;
    }

    /**
     * Is {@code limit} believable as this endpoint's tools cap?
     *
     * <p>Two checks, both about the number itself rather than about the
     * endpoint. It has to be small enough to explain the rejection: the
     * request carried {@code requestedCount} schemas and was refused for
     * being too long, so a "limit" at or above that count describes a
     * different field. And it has to be large enough to build a surface at
     * all ({@link #MIN_PLAUSIBLE_LIMIT}). An implausible number is dropped
     * rather than stored, because a wrong value here is permanent for the
     * life of the pod.
     */
    private static boolean plausible(
            int limit, int requestedCount, String modelLabel, String errorText) {
        if (limit >= MIN_PLAUSIBLE_LIMIT && (requestedCount <= 0 || limit < requestedCount)) {
            return true;
        }
        log.error("Tool-surface: endpoint '{}' rejected {} tool schemas and the message names "
                        + "maximum length {} — not believable as a tools cap, ignoring it. Set "
                        + "'maxTools:' in the model document (or the provider's _provider.yaml) "
                        + "to fix this permanently. Message: {}",
                modelLabel, requestedCount, limit, abbreviate(errorText));
        return false;
    }

    private static String normalize(String label) {
        return label.trim().toLowerCase(Locale.ROOT);
    }

    private static String abbreviate(String text) {
        if (text == null) return "(none)";
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 200 ? oneLine : oneLine.substring(0, 200) + "…";
    }
}
