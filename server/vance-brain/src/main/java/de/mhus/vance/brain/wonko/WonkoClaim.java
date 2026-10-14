package de.mhus.vance.brain.wonko;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/** A checkable statement of the subject, produced by {@code DECOMPOSE}. */
@Data
@NoArgsConstructor
public class WonkoClaim {

    private String id = "";

    private String text = "";

    private @Nullable String anchor;

    /** Why the decomposition produced this claim. */
    private @Nullable String reason;

    /** Search terms for locating references (incl. synonyms). */
    private List<String> searchTerms = new ArrayList<>();

    /** Ids of the passages {@code LOCATE} selected for this claim. */
    private List<String> passageIds = new ArrayList<>();

    /** Passages already compared against this claim — a new passage triggers one more compare. */
    private List<String> comparedPassageIds = new ArrayList<>();
}
