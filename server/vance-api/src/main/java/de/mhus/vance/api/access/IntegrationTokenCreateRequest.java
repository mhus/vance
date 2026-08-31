package de.mhus.vance.api.access;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /brain/{tenant}/integration-tokens}.
 *
 * <p>There is no {@code username} field: a caller mints for themselves only.
 * Minting on behalf of somebody else would be a delegation surface, and the
 * one thing it could add — an integration acting as another account — is
 * exactly what the confinement is meant to prevent.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@GenerateTypeScript("access")
public class IntegrationTokenCreateRequest {

    /** Id of an {@code IntegrationScopeProfile}, e.g. {@code links-capture}. */
    @NotBlank
    private String scopeProfile;

    /** Project the token is confined to. Required by most profiles. */
    private @Nullable String projectId;

    /** What the owner calls it — shown in their token list. */
    @NotBlank
    private String label;

    /**
     * Lifetime in days. Long is fine — revocation, not expiry, is the
     * control here — but not unbounded: a token nobody remembers minting
     * should eventually stop on its own.
     */
    private int expiresInDays;
}
