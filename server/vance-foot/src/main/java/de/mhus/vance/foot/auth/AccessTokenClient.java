package de.mhus.vance.foot.auth;

import de.mhus.vance.api.access.AccessTokenRequest;
import de.mhus.vance.api.access.AccessTokenResponse;

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
    AccessTokenResponse mint(String httpBase, String tenant, String username,
                             AccessTokenRequest request) throws Exception;
}
