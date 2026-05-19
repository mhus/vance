package de.mhus.vance.api.oauth;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One entry in the response of {@code GET /brain/{tenant}/oauth/providers}.
 * Lists the OAuth provider configurations the tenant has installed and
 * whether the calling user has already connected.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("oauth")
public class OAuthProviderListEntry {

    /** Stable id, also used as the URL path variable for init/callback. */
    private String providerId;

    /** Bean-type id (oidc, generic-oauth2, slack, atlassian, google, github, …). */
    private String typeId;

    /** {@code true} when the caller has stored OAuth tokens for this provider. */
    private boolean connected;
}
