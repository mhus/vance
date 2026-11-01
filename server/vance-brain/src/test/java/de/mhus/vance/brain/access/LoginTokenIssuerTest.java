package de.mhus.vance.brain.access;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.jwt.JwtService;
import de.mhus.vance.shared.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Every login ends in the issuer, so it is the one place that remembers when a user
 * last signed in — the fact the idle policy reads to tell a project nobody uses.
 */
class LoginTokenIssuerTest {

    private JwtService jwtService;
    private UserService userService;
    private LoginTokenIssuer issuer;

    @BeforeEach
    void setUp() {
        jwtService = mock(JwtService.class);
        userService = mock(UserService.class);
        when(jwtService.createToken(any(), any(), any(), any(), any())).thenReturn("token");
        issuer = new LoginTokenIssuer(jwtService, mock(HomeBootstrapService.class), userService);
    }

    @Test
    void aLogin_recordsWhenTheUserSignedIn() {
        issuer.issue("acme", "mike", true, null);

        verify(userService).recordLogin("acme", "mike");
    }

    @Test
    void refreshingASession_countsAsPresenceToo() {
        issuer.reissueAccess("acme", "mike", null);

        verify(userService).recordLogin("acme", "mike");
    }
}
