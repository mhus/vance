package de.mhus.vance.foot.auth;

import de.mhus.vance.api.access.AccessTokenRequest;
import de.mhus.vance.api.access.AccessTokenResponse;
import de.mhus.vance.api.access.EmailTokenStartResponse;
import de.mhus.vance.api.access.EmailTokenVerifyRequest;
import de.mhus.vance.api.access.LoginMethodsResponse;
import org.jspecify.annotations.Nullable;

/**
 * Mints a JWT at the brain's {@code POST /brain/{tenant}/access/{username}}
 * endpoint. Extracted behind an interface so {@link FootAuthService}'s
 * token cascade can be unit-tested without real HTTP.
 */
public interface AccessTokenClient {

    /**
     * @param httpBase brain HTTP base URL (e.g. {@code https://brain.example.com})
     * @param tenant   tenant path segment
     * @param username username path segment (the user the token is minted for)
     * @param request  credential (password or refresh token) + options
     * @return the minted access token (plus a refresh token when requested)
     * @throws Exception on transport failure or a non-200 response
     */
    AccessTokenResponse mint(String httpBase, String tenant, String username, AccessTokenRequest request)
            throws Exception;

    /**
     * {@code GET /brain/{tenant}/login-methods}: how the tenant signs in.
     *
     * @return the answer, or {@code null} when the server cannot say (older brain,
     *         unreachable) — the caller then keeps the password login
     */
    @Nullable
    LoginMethodsResponse loginMethods(String httpBase, String tenant);

    /**
     * {@code POST /brain/{tenant}/access/{username}/email-token}: asks the brain to
     * mail a sign-in code. The answer is the same for every username.
     *
     * @throws Exception on transport failure or a non-200 response (429: asked too often)
     */
    EmailTokenStartResponse startEmailToken(String httpBase, String tenant, String username) throws Exception;

    /**
     * {@code POST /brain/{tenant}/access/{username}/email-token/verify}: redeems the
     * code for tokens.
     *
     * @throws Exception on transport failure or a non-200 response (401: wrong or spent code)
     */
    AccessTokenResponse verifyEmailToken(
            String httpBase, String tenant, String username, EmailTokenVerifyRequest request) throws Exception;
}
