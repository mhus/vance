package de.mhus.vance.api.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Payload for a {@link MessageType#SESSION_LIST} request.
 *
 * <p>Allowed with or without a bound session. Returns the caller's sessions
 * in the current tenant; if {@code projectId} is set the list is narrowed
 * to that project.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("ws")
public class SessionListRequest {

    /** Optional {@code ProjectDocument.name} filter. */
    private @Nullable String projectId;
}
