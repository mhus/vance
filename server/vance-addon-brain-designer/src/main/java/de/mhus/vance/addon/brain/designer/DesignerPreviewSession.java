package de.mhus.vance.addon.brain.designer;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A freshly minted preview session — what
 * {@code POST /addon/designer/preview-session} returns. The token is a
 * short-lived {@code DESIGN_PREVIEW} JWT that the client embeds as a
 * path segment in the sandboxed content route; see
 * {@code DesignerContentController} for why it travels in the path and
 * not as a query parameter.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("designer")
public class DesignerPreviewSession {

    /** The opaque JWT. Treat it as a credential: read-only, app-scoped, short-lived. */
    private String token;

    /** Expiry instant — the client re-mints after this. */
    private Instant expiresAt;
}
