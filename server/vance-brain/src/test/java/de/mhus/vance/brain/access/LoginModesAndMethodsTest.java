package de.mhus.vance.brain.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.access.LoginMethodsResponse;
import de.mhus.vance.shared.user.EmailTokenMode;
import org.junit.jupiter.api.Test;

class LoginModesAndMethodsTest {

    @Test
    void thePasswordSignsInUnlessTheEmailCodeReplacesIt() {
        assertThat(TestLoginModes.of(false, true).passwordLoginEnabled("acme")).isTrue();
        assertThat(TestLoginModes.of(true, true).passwordLoginEnabled("acme")).isFalse();
    }

    @Test
    void theMethodsFollowTheTwoSwitchesIndependently() {
        for (boolean emailToken : new boolean[] {false, true}) {
            for (boolean sso : new boolean[] {false, true}) {
                LoginMethodsResponse methods = new LoginMethodsController(TestLoginModes.of(emailToken, sso))
                        .methods("acme")
                        .getBody();

                assertThat(methods).isNotNull();
                assertThat(methods.isEmailToken()).isEqualTo(emailToken);
                assertThat(methods.isPassword()).isEqualTo(!emailToken);
                assertThat(methods.isSso()).isEqualTo(sso);
            }
        }
    }

    @Test
    void theEmailCodeIsDecidedPerTenant() {
        EmailTokenMode mode = mock(EmailTokenMode.class);
        when(mode.enabledFor("mhus")).thenReturn(true);
        LoginModes modes = new LoginModes(mode, false);
        LoginMethodsController controller = new LoginMethodsController(modes);

        LoginMethodsResponse mhus = controller.methods("mhus").getBody();
        LoginMethodsResponse apple = controller.methods("apple").getBody();

        assertThat(mhus).isNotNull();
        assertThat(mhus.isEmailToken()).isTrue();
        assertThat(mhus.isPassword()).isFalse();
        assertThat(apple).isNotNull();
        assertThat(apple.isEmailToken()).isFalse();
        assertThat(apple.isPassword()).isTrue();
        assertThat(modes.passwordLoginEnabled("apple")).isTrue();
        assertThat(modes.passwordLoginEnabled("mhus")).isFalse();
    }
}
