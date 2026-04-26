package de.mhus.vance.api.thinkprocess;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Outcome of an asynchronously-dispatched tool call, as reported back
 * to the calling engine via {@code SteerMessage.ToolResult}.
 */
@GenerateTypeScript("thinkprocess")
public enum ToolCallStatus {
    SUCCESS,
    ERROR,
    TIMEOUT
}
