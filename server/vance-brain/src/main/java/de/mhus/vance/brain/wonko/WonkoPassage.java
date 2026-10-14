package de.mhus.vance.brain.wonko;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/** A reference passage selected for a claim — the material a comparison runs against. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WonkoPassage {

    private String id = "";

    /** Reference document path. */
    private String ref = "";

    /** The passage text (a paragraph of the reference). */
    private String text = "";

    /** Why {@code LOCATE} considered it applicable. */
    private @Nullable String reason;
}
