package de.mhus.vance.api.vogon;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Specification of a checkpoint inside a phase — a place where the
 * strategy pauses and asks the session-owner via the inbox.
 * Vogon turns this into a real {@code InboxItemDocument} when the
 * phase reaches it.
 *
 * <p>See {@code specification/vogon-engine.md} §2.3 for the full
 * schema. v1 supports {@code APPROVAL/DECISION/FEEDBACK}; the
 * {@code timeoutSeconds}/{@code onTimeout} bits land in v2.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckpointSpec {

    private CheckpointType type = CheckpointType.APPROVAL;

    /** User-facing question text. May contain {@code ${…}} substitutions. */
    private String message = "";

    /** {@code DECISION}: choices the user picks from. */
    @Builder.Default
    private List<String> options = new ArrayList<>();

    /** Strategy-state key under which the answer value is stored
     *  for later flag-evaluation. Required when the checkpoint
     *  result drives downstream gates. */
    private @Nullable String storeAs;

    /** Same criticality semantics as inbox: LOW + default = auto-answered. */
    private @Nullable String criticality;

    /** Default value for LOW-criticality auto-answer. */
    private @Nullable Object defaultValue;

    /** Tags propagated onto the inbox item. */
    @Builder.Default
    private List<String> tags = new ArrayList<>();

    /** Optional extra payload merged onto the inbox item's payload. */
    @Builder.Default
    private Map<String, Object> payload = new LinkedHashMap<>();
}
