package de.mhus.vance.api.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Payload for a {@link MessageType#PROJECT_LIST} request.
 *
 * <p>Returns all projects in the caller's tenant; if
 * {@code projectGroupId} is set the list is narrowed to that group.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("ws")
public class ProjectListRequest {

    /** Optional {@code ProjectGroupDocument.name} filter. */
    private @Nullable String projectGroupId;
}
