package de.mhus.vance.api.zaphod;

import java.util.ArrayList;
import java.util.List;
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

    /** Mongo-id of the spawned sub-process; set after the first
     *  spawn. For {@code debate}, the same sub-process is reused
     *  across rounds — see spec §5. */
    private @Nullable String spawnedProcessId;

    /** Replies of this head, one per round. Index = round number.
     *  Length equals {@code state.currentRound + 1} once the head
     *  has produced an answer for the current round. */
    @Builder.Default
    private List<String> replies = new ArrayList<>();

    @Builder.Default
    private HeadStatus status = HeadStatus.PENDING;

    /** Set when {@link #status} = {@link HeadStatus#FAILED}. */
    private @Nullable String failureReason;

    /** Convenience accessor: latest reply of this head (i.e. the
     *  reply for the most recently completed round), or {@code null}
     *  if the head has never produced a reply. */
    public @Nullable String getLastReply() {
        return replies == null || replies.isEmpty()
                ? null : replies.get(replies.size() - 1);
    }
}
