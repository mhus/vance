package de.mhus.vance.brain.wonko;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/** One entry of the mandate log — every category change with its reason (plan §6.4). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WonkoMandateChange {

    private int round;

    /** Who changed it: {@code wonko} or {@code user}. */
    private String by = "";

    private String change = "";

    private @Nullable String reason;
}
