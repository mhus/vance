package de.mhus.vance.brain.wonko;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/** One mandate category: how Wonko may handle findings that fall into it. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WonkoCategory {

    @Builder.Default
    private MandateLevel level = MandateLevel.CONFIRM;

    private @Nullable String description;

    /** Wonko may not change the level of a locked category. */
    private boolean locked;

    /** How far Wonko may loosen this category on its own; {@code null} = not at all. */
    private @Nullable MandateLevel ceiling;

    /** {@code true} when Wonko created the category itself (§6.4). */
    private boolean createdByWonko;
}
