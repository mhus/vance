package de.mhus.vance.api.oauthserver;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * What the consent page shows about a pending authorization request.
 *
 * <p>{@link #clientName} is what the client says about itself (or, for
 * {@code cimd}, what the document at its URL says); {@link #redirectHost} is
 * the one fact the server can vouch for — where the code will go. The page
 * must show both, and warn when the redirect is a loopback address that any
 * program on the user's machine could listen on.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("oauthserver")
public class OAuthConsentRequestDto {

    private String requestId;

    /** Must be echoed in the decision — binds the decision to this page view. */
    private String consentTicket;

    private String tenantId;

    /** The logged-in user the grant will act as. */
    private String userId;

    private String clientId;

    private String clientName;

    /** {@code dcr} (self-registered), {@code cimd} (metadata document) or {@code first-party}. */
    private String clientKind;

    private @Nullable String clientUri;

    private String redirectUri;

    private String redirectHost;

    /** The redirect goes to the user's own machine (loopback). */
    private boolean loopbackRedirect;

    /** Granted scope: {@code mcp}, {@code mcp-project}, {@code agents} or {@code account}. */
    private String scope;

    /** The door URL the token will be bound to, or {@code null} for {@code account}. */
    private @Nullable String resource;

    /** Project of the {@code mcp-project} door. */
    private @Nullable String projectId;
}
