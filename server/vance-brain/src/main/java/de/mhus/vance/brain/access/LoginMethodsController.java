package de.mhus.vance.brain.access;

import de.mhus.vance.api.access.LoginMethodsResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /brain/{tenant}/login-methods} — which sign-in ways the
 * tenant offers. Open: the login page asks before there is a token. Whether the
 * tenant signs in with a code is a tenant fact; the answer says nothing about any account.
 */
@RestController
public class LoginMethodsController {

    private final LoginModes loginModes;

    public LoginMethodsController(LoginModes loginModes) {
        this.loginModes = loginModes;
    }

    @GetMapping("/brain/{tenant}/login-methods")
    public ResponseEntity<LoginMethodsResponse> methods(@PathVariable("tenant") String tenant) {
        return ResponseEntity.ok(LoginMethodsResponse.builder()
                .password(loginModes.passwordLoginEnabled(tenant))
                .emailToken(loginModes.emailTokenEnabled(tenant))
                .sso(loginModes.ssoEnabled())
                .build());
    }
}
