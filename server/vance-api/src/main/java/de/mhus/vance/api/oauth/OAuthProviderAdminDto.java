package de.mhus.vance.api.oauth;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Admin-view of one provider config. Returned by {@code GET
 * /brain/{tenant}/admin/oauth/providers} and the single-provider GET.
 * The YAML body round-trips verbatim so the admin form can edit it
 * without losing comments or field order.
 *
 * <p>The client secret is intentionally absent — admins see whether
 * one is set ({@link #hasClientSecret}) but the plaintext never
 * leaves the server. To rotate, the admin sends a new value via the
 * write endpoint.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("oauth")
public class OAuthProviderAdminDto {

    /** Stable id, also the YAML stem (e.g. {@code slack}, {@code keycloak-acme}). */
    private String providerId;

    /** Bean-type id (oidc, generic-oauth2, slack, atlassian, google, …). */
    private String typeId;

    /** OAuth-App identifier registered with the provider. Not secret. */
    private String clientId;

    /** {@code true} when a tenant-PASSWORD setting holds the client secret. */
    private boolean hasClientSecret;

    /** Verbatim YAML body of the {@code oauth/<providerId>.yaml} document. */
    private @Nullable String yaml;
}
