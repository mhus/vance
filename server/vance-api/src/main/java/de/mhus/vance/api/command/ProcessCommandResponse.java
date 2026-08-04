package de.mhus.vance.api.command;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Reply to a {@link ProcessCommandRequest}. Carries the dispatch
 * {@link #outcome} plus an optional human-readable {@link #message} and
 * a handler-supplied {@link #value}. See {@code planning/engine-commands.md} §2.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("command")
public class ProcessCommandResponse {

    private String processName;

    /** Echo of the dispatched command verb. */
    private String command;

    private EngineCommandOutcome outcome;

    /** Human-readable detail (error text, unknown-verb note, …). */
    private @Nullable String message;

    /** Optional structured result the handler returned. */
    private @Nullable Object value;
}
