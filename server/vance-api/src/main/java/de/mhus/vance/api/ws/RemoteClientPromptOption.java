package de.mhus.vance.api.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One selectable answer to a {@link RemoteClientPrompt}.
 *
 * <p>{@link #value} is the literal line that gets submitted when the watcher
 * picks this option — so the remote answer travels the exact same path a typed
 * answer would, and the client needs no separate answer protocol.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("ws")
public class RemoteClientPromptOption {

    /** Button caption, e.g. {@code "allow once"}. */
    private String label;

    /** The input line this option submits, e.g. {@code "1"}. */
    private String value;
}
