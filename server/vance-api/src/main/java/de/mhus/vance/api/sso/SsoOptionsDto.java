package de.mhus.vance.api.sso;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Whether the login page offers an SSO sign-in — for a typed address
 * ({@code GET /brain/_sso/discover}) or for the tenant the page is bound to
 * ({@code GET /brain/_sso/options}, asked once the person entered the tenant:
 * {@link #sso} means the tenant supports Google sign-in). Never names a tenant.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@GenerateTypeScript("sso")
public class SsoOptionsDto {

    /** {@code true} when "Continue with …" should be offered. */
    private boolean sso;

    /** Provider key (e.g. {@code google}); {@code null} when {@link #sso} is false. */
    private @Nullable String provider;

    /**
     * Tenant options only: a work-domain rule points at the tenant, so the
     * sign-in needs an e-mail address (the username, if it is one, or a typed
     * one).
     */
    private boolean domains;

    /** Tenant options only: the tenant accepts prepared private Google accounts — no address needed. */
    private boolean consumer;
}
