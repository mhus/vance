package de.mhus.vance.api.scripts;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Generic payload for the {@code script-execution-*} WebSocket
 * notifications. The {@code type}-field of the envelope distinguishes
 * the four lifecycle stages:
 *
 * <ul>
 *   <li>{@code script-execution-started}  → {@code startedAtMs} set</li>
 *   <li>{@code script-execution-log}      → {@code logLine} set</li>
 *   <li>{@code script-execution-finished} → {@code resultValue},
 *       {@code durationMs}, {@code endedAtMs} set</li>
 *   <li>{@code script-execution-failed}   → {@code errorMessage},
 *       {@code endedAtMs} set</li>
 *   <li>{@code script-execution-cancelled} → {@code endedAtMs} set</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("scripts")
public class ScriptExecutionEventData {

    private String executionId;

    private @Nullable Long startedAtMs;
    private @Nullable Long endedAtMs;
    private @Nullable Long durationMs;
    private @Nullable Object resultValue;
    private @Nullable String errorMessage;

    /** One log line. Stream channel: {@code stdout}, {@code stderr}, {@code log}. */
    private @Nullable String stream;
    private @Nullable String logLine;
}
