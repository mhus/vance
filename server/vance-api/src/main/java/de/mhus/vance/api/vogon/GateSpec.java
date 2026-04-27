package de.mhus.vance.api.vogon;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Gate condition for advancing past a phase. v1 supports an
 * AND-list ({@link #requires}) and an OR-list ({@link #requiresAny}).
 *
 * <p>An empty gate (both lists empty) means "no gate" — Vogon
 * advances as soon as the phase's intrinsic activity (worker /
 * checkpoint) is done. See {@code specification/vogon-engine.md}
 * §2.2.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GateSpec {

    /** ALL of these flags must be {@code true}. */
    @Builder.Default
    private List<String> requires = new ArrayList<>();

    /** AT LEAST ONE of these flags must be {@code true}. */
    @Builder.Default
    private List<String> requiresAny = new ArrayList<>();
}
