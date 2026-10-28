package de.mhus.vance.brain.tools.runner;

import de.mhus.vance.toolpack.ToolException;

/**
 * The runner understood the call and said no — with the stable {@code code} its operation
 * gave. A {@link ToolException} for anything that just shows the message; callers that act on
 * the reason (the audio tools map it to their own vocabulary) read {@link #code()}.
 */
public class RunnerRefusedException extends ToolException {

    private final String code;

    public RunnerRefusedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
