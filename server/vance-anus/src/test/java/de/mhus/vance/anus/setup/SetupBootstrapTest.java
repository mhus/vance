package de.mhus.vance.anus.setup;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SetupBootstrapTest {

    @BeforeEach
    @AfterEach
    void resetStaticState() {
        SetupBootstrap.reset();
    }

    @Test
    void parse_withoutSetupFlag_leavesArgsUntouchedAndSetupModeOff() {
        String[] remaining = SetupBootstrap.parse(new String[] {"--spring.profiles.active=dev"});

        assertThat(remaining).containsExactly("--spring.profiles.active=dev");
        assertThat(SetupBootstrap.isSetupMode()).isFalse();
    }

    @Test
    void parse_setupFlag_stripsAndEnablesMode() {
        String[] remaining = SetupBootstrap.parse(new String[] {"--setup"});

        assertThat(remaining).isEmpty();
        assertThat(SetupBootstrap.isSetupMode()).isTrue();
    }

    @Test
    void parse_setupFlagAmongSpringArgs_keepsSpringArgsAndEnablesMode() {
        String[] remaining = SetupBootstrap.parse(new String[] {
                "--spring.profiles.active=dev",
                "--setup",
                "--vance.anus.access.timeout=10m"
        });

        assertThat(remaining).containsExactly(
                "--spring.profiles.active=dev",
                "--vance.anus.access.timeout=10m");
        assertThat(SetupBootstrap.isSetupMode()).isTrue();
    }

    @Test
    void parse_repeatedSetupFlags_stillSingleMode() {
        String[] remaining = SetupBootstrap.parse(new String[] {"--setup", "--setup"});

        assertThat(remaining).isEmpty();
        assertThat(SetupBootstrap.isSetupMode()).isTrue();
    }

    @Test
    void parse_clearsModeWhenFlagAbsent() {
        SetupBootstrap.parse(new String[] {"--setup"});
        assertThat(SetupBootstrap.isSetupMode()).isTrue();

        SetupBootstrap.parse(new String[] {"--spring.profiles.active=dev"});
        assertThat(SetupBootstrap.isSetupMode()).isFalse();
    }
}
