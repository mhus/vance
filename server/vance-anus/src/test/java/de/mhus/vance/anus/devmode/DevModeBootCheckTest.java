package de.mhus.vance.anus.devmode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class DevModeBootCheckTest {

    @Test
    void boot_devModeOnAndProdProfileActive_throws() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        assertThatThrownBy(() -> new DevModeBootCheck(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dev-mode")
                .hasMessageContaining("prod");
    }

    @Test
    void boot_devModeOnAndProductionProfileActive_throws() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("production");

        assertThatThrownBy(() -> new DevModeBootCheck(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("production");
    }

    @Test
    void boot_profileMatchIsCaseInsensitive() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("PROD");

        assertThatThrownBy(() -> new DevModeBootCheck(env))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void boot_devModeOnWithoutProdProfile_succeeds() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev", "local");

        DevModeBootCheck check = new DevModeBootCheck(env);

        assertThat(check).isNotNull();
    }

    @Test
    void boot_devModeOnWithNoActiveProfiles_succeeds() {
        MockEnvironment env = new MockEnvironment();

        DevModeBootCheck check = new DevModeBootCheck(env);

        assertThat(check).isNotNull();
    }

    @Test
    void boot_anotherProfileNamedProductionLike_doesNotMatch() {
        // Only literal 'prod' / 'production' trip the guard — neighbouring
        // names like 'preprod' or 'productionlike' must not throw.
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("preprod", "productionlike");

        DevModeBootCheck check = new DevModeBootCheck(env);

        assertThat(check).isNotNull();
    }
}
