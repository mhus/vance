package de.mhus.vance.api.access;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /{tenant}/access/{username}} — the credential
 * for minting a fresh access token.
 *
 * <p>Exactly one of the two credential fields must be set:
 * <ul>
 *   <li>{@link #password} — the user's plaintext password (TLS) verified
 *       against the stored hash. The standard interactive login.</li>
 *   <li>{@link #refreshToken} — a previously-issued refresh JWT (30 d
 *       lifetime). Lets long-running clients (web UI, foot daemon) renew
 *       their access token without re-prompting the user. The refresh
 *       token must encode the same {@code tenant} and {@code username}
 *       as the URL path — cross-user reuse is rejected.</li>
 * </ul>
 *
 * <p>{@link #requestRefreshToken} additionally requests a new refresh
 * token alongside the access token in the response. Default {@code false}
 * — only callers that need the rolling-refresh pattern should set it.
 *
 * <p>{@link #requestCookies} switches the response to also set the web-UI
 * session cookies ({@code vance_access}, optionally {@code vance_refresh},
 * and the JS-readable {@code vance_data}). When false (the default), the
 * tokens are returned only in the JSON body — that's the right shape for
 * the CLI clients. When true, an SPA can rely on browser-managed
 * {@code HttpOnly} storage without JavaScript ever holding the bearer
 * token.
 *
 * <p>{@link #includeWebUiSettings} is a request to also embed the
 * caller's {@code _user_<login>} project settings whose keys start with
 * {@code webui.} into the {@code vance_data} cookie. Only meaningful
 * when {@code requestCookies=true}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("access")
public class AccessTokenRequest {

    private @Nullable String password;

    private @Nullable String refreshToken;

    private boolean requestRefreshToken;

    private boolean requestCookies;

    private boolean includeWebUiSettings;
}
