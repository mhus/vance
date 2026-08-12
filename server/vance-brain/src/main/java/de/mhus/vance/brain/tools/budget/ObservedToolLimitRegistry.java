package de.mhus.vance.brain.tools.budget;

import de.mhus.vance.brain.ai.ToolLimitError;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
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
        String key = normalize(modelLabel);
        Integer previous = observed.get(key);
        if (previous != null && previous <= limit) {
            // Already know an equal or stricter limit — nothing to learn.
            return OptionalInt.of(previous);
        }
        observed.put(key, limit);
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

    /** Test / admin hook: forget everything learned so far. */
    public void clear() {
        observed.clear();
        version.incrementAndGet();
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
