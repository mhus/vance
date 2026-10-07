package de.mhus.vance.api.admin;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One line of an admin action's result — the generic per-item row the
 * client renders without knowing the action (a model in the health
 * check, a skipped provider instance in discovery, a research source
 * …). {@link #key} names the item, {@link #ok} says whether it worked,
 * {@link #detail} carries the message — the reason it failed, or a
 * short outcome like the hit count.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@GenerateTypeScript("admin")
public class AdminActionItemDto {

    /** Item name — model spec, source name, whatever the action checked. */
    private String key;

    /** Whether the check over this item passed. */
    private boolean ok;

    /** Outcome message — failure reason or short result. */
    private @Nullable String detail;

    /** Wall-clock duration of this item's check, in milliseconds. */
    private long durationMs;
}
