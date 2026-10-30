package de.mhus.vance.api.access;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Answer to {@code GET /brain/{tenant}/login-methods}: which sign-in ways this
 * installation offers, so the login page shows only those. Says nothing about
 * any account.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@GenerateTypeScript("access")
public class LoginMethodsResponse {

    /** Sign in with a password. Off when the e-mail code replaces it. */
    private boolean password;

    /** Sign in with a code sent by e-mail. */
    private boolean emailToken;

    /** Sign in through an identity provider (Google). */
    private boolean sso;
}
