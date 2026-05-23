package de.mhus.vance.shared.toolhealth;

import de.mhus.vance.api.toolhealth.ToolHealthClassification;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One active cooldown entry. Tells {@code AgrajagChecker} not to enqueue
 * (or write) anything for the next attempt with the same
 * {@code errorSignature} (and {@code userId}, if set) before
 * {@link #nextSpawnAllowedAt}.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ToolHealthCooldown {

    /** Short stable key derived from the error (e.g. {@code http-403}, {@code timeout}). */
    private String errorSignature = "";

    /** Only set when the cooldown is user-specific (typical for permission). */
    private @Nullable String userId;

    private Instant nextSpawnAllowedAt = Instant.EPOCH;

    /** How often this signature has matched — drives exponential backoff. */
    private int hits;

    private @Nullable ToolHealthClassification lastClassification;

    private @Nullable Instant lastTriggeredAt;

    /** Optional audit note carried from the pattern config or the engine. */
    private @Nullable String note;
}
