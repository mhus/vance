package de.mhus.vance.brain.wonko;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/** One item of the check queue (plan §4a). The queue is Wonko's working state. */
@Data
@NoArgsConstructor
public class WonkoItem {

    private String id = "";

    private WonkoItemType type = WonkoItemType.CHECK;

    /** Claim the item works on ({@code LOCATE}, {@code COMPARE}). */
    private @Nullable String claimId;

    /** Reference document ({@code COVERAGE}). */
    private @Nullable String ref;

    /** Extra search terms ({@code LOCATE} follow-ups of cross-references). */
    private List<String> terms = new ArrayList<>();

    /** Follow-up depth for {@code LOCATE} — cross-references are followed a bounded number of times. */
    private int depth;

    /** {@code COMPARE} again after the user answered something about this claim. */
    private boolean recheck;

    /** Lower sorts first. */
    private int priority;

    static WonkoItem of(String id, WonkoItemType type, int priority) {
        WonkoItem item = new WonkoItem();
        item.setId(id);
        item.setType(type);
        item.setPriority(priority);
        return item;
    }
}
