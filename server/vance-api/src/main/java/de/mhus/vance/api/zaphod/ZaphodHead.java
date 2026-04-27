package de.mhus.vance.api.zaphod;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Runtime state of one head inside a Zaphod council. Persisted as
 * an entry in {@code ZaphodState.heads}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ZaphodHead {

    /** Unique-within-council name (used for sub-process naming
     *  and the synthesis prompt). */
    private String name = "";

    /** Recipe that defines this head's behaviour (typically a
     *  Ford recipe). */
    private String recipe = "";

    /** Optional persona-block appended to the steer message —
     *  the head's "personality" on top of the recipe's role. */
    private @Nullable String persona;

    /** Mongo-id of the spawned sub-process; set after spawn. */
    private @Nullable String spawnedProcessId;

    /** Last assistant message produced by this head. {@code null}
     *  while pending or on failure. */
    private @Nullable String reply;

    @Builder.Default
    private HeadStatus status = HeadStatus.PENDING;

    /** Set when {@link #status} = {@link HeadStatus#FAILED}. */
    private @Nullable String failureReason;
}
