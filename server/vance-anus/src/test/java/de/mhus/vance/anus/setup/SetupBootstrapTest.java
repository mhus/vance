package de.mhus.vance.anus.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        String[] remaining = SetupBootstrap.parse(
                new String[] {"--spring.profiles.active=dev", "--setup", "--vance.anus.access.timeout=10m"});

        assertThat(remaining).containsExactly("--spring.profiles.active=dev", "--vance.anus.access.timeout=10m");
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

    @Test
    void parse_configFlag_stripsValueAndExposesIt() {
        String[] remaining = SetupBootstrap.parse(new String[] {"--setup", "--config", "tenant.yaml"});

        assertThat(remaining).isEmpty();
        assertThat(SetupBootstrap.isSetupMode()).isTrue();
        assertThat(SetupBootstrap.configSource()).isEqualTo("tenant.yaml");
        assertThat(SetupBootstrap.isDryRun()).isFalse();
    }

    @Test
    void parse_stdinConfigWithDryRun_agentMode() {
        String[] remaining = SetupBootstrap.parse(new String[] {"--setup", "--config", "-", "--dry-run"});

        assertThat(remaining).isEmpty();
        assertThat(SetupBootstrap.configSource()).isEqualTo("-");
        assertThat(SetupBootstrap.isDryRun()).isTrue();
    }

    @Test
    void parse_configWithoutValue_isUsageError() {
        assertThatThrownBy(() -> SetupBootstrap.parse(new String[] {"--setup", "--config"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--config needs a value");
    }

    @Test
    void parse_dryRunWithoutConfig_isUsageError() {
        assertThatThrownBy(() -> SetupBootstrap.parse(new String[] {"--setup", "--dry-run"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--dry-run needs --config");
    }

    @Test
    void parse_composeModeFlags_areNotStolenBySetupBootstrap() {
        // SetupBootstrap parses before the compose bootstrap — it must not
        // consume a --config that belongs to --setup-docker-compose.
        String[] remaining =
                SetupBootstrap.parse(new String[] {"--setup-docker-compose", "--config", "-", "--dry-run"});

        assertThat(SetupBootstrap.isSetupMode()).isFalse();
        assertThat(SetupBootstrap.configSource()).isNull();
        assertThat(SetupBootstrap.isDryRun()).isFalse();
        assertThat(remaining).containsExactly("--setup-docker-compose", "--config", "-", "--dry-run");
    }
}
