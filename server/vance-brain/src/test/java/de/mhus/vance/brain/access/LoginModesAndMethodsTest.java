package de.mhus.vance.brain.access;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.access.LoginMethodsResponse;
import org.junit.jupiter.api.Test;

class LoginModesAndMethodsTest {

    @Test
    void thePasswordSignsInUnlessTheEmailCodeReplacesIt() {
        assertThat(new LoginModes(false, true).passwordLoginEnabled()).isTrue();
        assertThat(new LoginModes(true, true).passwordLoginEnabled()).isFalse();
    }

    @Test
    void theMethodsFollowTheTwoSwitchesIndependently() {
        for (boolean emailToken : new boolean[] {false, true}) {
            for (boolean sso : new boolean[] {false, true}) {
                LoginMethodsResponse methods = new LoginMethodsController(new LoginModes(emailToken, sso))
                        .methods("acme")
                        .getBody();

                assertThat(methods).isNotNull();
                assertThat(methods.isEmailToken()).isEqualTo(emailToken);
                assertThat(methods.isPassword()).isEqualTo(!emailToken);
                assertThat(methods.isSso()).isEqualTo(sso);
            }
        }
    }
}
