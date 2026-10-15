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
 * ({@code GET /brain/_sso/options}, where {@link #sso} means a private Google
 * account may sign in). Never names a tenant.
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
     * Whether any e-mail domain signs in via SSO on this installation — the
     * login page then asks for the work address. Says nothing about which
     * domains or tenants.
     */
    private boolean domains;
}
