package de.mhus.vance.brain.access;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.user.EmailTokenMode;

/** {@link LoginModes} for tests: the e-mail code is on (or off) for every tenant. */
public final class TestLoginModes {

    private TestLoginModes() {}

    public static LoginModes of(boolean emailToken, boolean sso) {
        EmailTokenMode mode = mock(EmailTokenMode.class);
        when(mode.enabledFor(anyString())).thenReturn(emailToken);
        return new LoginModes(mode, sso);
    }
}
