package de.mhus.vance.brain.tools;

import de.mhus.vance.toolpack.ToolException;
import org.jspecify.annotations.Nullable;

/**
 * Thrown by a long-running server tool when its owning process was paused
 * (ESC / {@code /pause}) mid-operation. Extends {@link ToolException} so
 * the tool-dispatch surfaces it as a normal tool error; the engine then
 * bails at its next loop-head interrupt check. See {@link ToolInterruptChecker}.
 */
public class ToolInterruptedException extends ToolException {

    public ToolInterruptedException(@Nullable String processId) {
        super("Cancelled — the process was paused (ESC / /pause); aborting this "
                + "tool. process='" + processId + "'");
    }
}
