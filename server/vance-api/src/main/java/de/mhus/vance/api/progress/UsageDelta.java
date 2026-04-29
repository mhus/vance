package de.mhus.vance.api.progress;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Per-operation cost summary attached to a "completed" {@link StatusPayload}
 * (e.g. {@link StatusTag#TOOL_END}, {@link StatusTag#NODE_DONE},
 * {@link StatusTag#PHASE_DONE}). Mirrors {@link MetricsPayload} but scoped
 * to the work that happened between an operation's open and close pings —
 * not cumulative.
 *
 * <p>Token fields stay {@code 0} for operations that did not invoke an LLM
 * (pure filesystem tools, etc.); {@link #elapsedMs} is the only field
 * filled unconditionally — measured server-side as the wall-clock duration
 * of the operation as seen by the brain.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("progress")
public class UsageDelta {

    private int tokensIn;

    private int tokensOut;

    private int llmCalls;

    private long elapsedMs;

    private @Nullable String modelAlias;

    /** Optional cost in 1/1_000_000 EUR — server-side computation, may be omitted. */
    private @Nullable Long costMicros;
}
