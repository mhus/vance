package de.mhus.vance.api.progress;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Cumulative numeric counters for one think-process — the "Iron-Man-HUD"
 * payload. Emitted on every LLM round-trip from the AI layer; values
 * are also persisted on {@code ThinkProcessDocument} for quota auditing.
 *
 * <p>The {@code lastCall*} fields carry the delta of the round-trip that
 * just completed, so clients can render an incremental indicator
 * without diffing successive snapshots themselves.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("progress")
public class MetricsPayload {

    private long tokensInTotal;

    private long tokensOutTotal;

    private long charsInTotal;

    private long charsOutTotal;

    private int llmCallCount;

    private long elapsedMs;

    private @Nullable String modelAlias;

    private @Nullable Integer lastCallTokensIn;

    private @Nullable Integer lastCallTokensOut;

    private @Nullable Integer lastCallCharsIn;

    private @Nullable Integer lastCallCharsOut;

    /**
     * Context-window size of the model used for the last LLM call.
     * Clients can render a fill ratio by dividing
     * {@link #lastCallTokensIn} by this number ("the prompt we just sent
     * was X% of the model's context"). Null when the engine path doesn't
     * resolve a {@code ModelInfo} or the model has no declared context
     * window.
     */
    private @Nullable Integer contextWindowTokens;
}
