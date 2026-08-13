package de.mhus.vance.api.magrathea;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Request body for
 * {@code POST /brain/{tenant}/project/{project}/workflows/start-document}
 * — start a run from a document at a known path instead of by workflow
 * name.
 *
 * <p>The name-based sibling ({@link MagratheaStartRequest}) resolves
 * through the {@code _vance/workflows/} cascade and stays the route for
 * schedulers, hooks and agents: they know a name, not a location. This
 * one exists for the opposite situation — a user looking at an open
 * document who wants to run <em>that</em>, wherever it happens to live.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("magrathea")
public class MagratheaStartDocumentRequest {

    /**
     * Document path inside the project named in the URL. Cross-project
     * starts are not expressible here on purpose — the project is the
     * path's scope, so this endpoint can never reach into another one.
     */
    @NotBlank
    private String path;

    /** Caller-supplied parameters, validated against the workflow's {@code parameters:} block. */
    private @Nullable Map<String, Object> params;

    /** Audit hint; the controller defaults it to the authenticated user when blank. */
    private @Nullable String startedBy;
}
