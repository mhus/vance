package de.mhus.vance.brain.access;

import de.mhus.vance.api.access.LoginMethodsResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /brain/{tenant}/login-methods} — which sign-in ways the
 * installation offers. Open: the login page asks before there is a token. The
 * answer is the same for every tenant name and says nothing about any account.
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
                .password(loginModes.passwordLoginEnabled())
                .emailToken(loginModes.emailTokenEnabled())
                .sso(loginModes.ssoEnabled())
                .build());
    }
}
