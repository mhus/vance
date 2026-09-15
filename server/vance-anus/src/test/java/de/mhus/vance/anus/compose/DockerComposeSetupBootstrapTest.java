package de.mhus.vance.anus.compose;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DockerComposeSetupBootstrapTest {

    @AfterEach
    void tearDown() {
        DockerComposeSetupBootstrap.reset();
    }

    @Test
    void parse_stripsFlagAndEnablesMode() {
        String[] remaining = DockerComposeSetupBootstrap.parse(new String[] {"foo", "--setup-docker-compose", "bar"});

        assertThat(remaining).containsExactly("foo", "bar");
        assertThat(DockerComposeSetupBootstrap.isMode()).isTrue();
    }

    @Test
    void parse_withoutFlag_leavesModeOff() {
        String[] remaining = DockerComposeSetupBootstrap.parse(new String[] {"chat"});

        assertThat(remaining).containsExactly("chat");
        assertThat(DockerComposeSetupBootstrap.isMode()).isFalse();
    }

    @Test
    void parse_configFlag_stripsValueAndExposesIt() {
        String[] remaining =
                DockerComposeSetupBootstrap.parse(new String[] {"--setup-docker-compose", "--config", "setup.yaml"});

        assertThat(remaining).isEmpty();
        assertThat(DockerComposeSetupBootstrap.isMode()).isTrue();
        assertThat(DockerComposeSetupBootstrap.configSource()).isEqualTo("setup.yaml");
        assertThat(DockerComposeSetupBootstrap.isDryRun()).isFalse();
    }

    @Test
    void parse_stdinConfigWithDryRun_agentMode() {
        String[] remaining = DockerComposeSetupBootstrap.parse(
                new String[] {"--setup-docker-compose", "--config", "-", "--dry-run"});

        assertThat(remaining).isEmpty();
        assertThat(DockerComposeSetupBootstrap.configSource()).isEqualTo("-");
        assertThat(DockerComposeSetupBootstrap.isDryRun()).isTrue();
    }

    @Test
    void parse_configWithoutValue_isUsageError() {
        assertThatThrownBy(() -> DockerComposeSetupBootstrap.parse(new String[] {"--setup-docker-compose", "--config"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--config needs a value");
    }

    @Test
    void parse_dryRunWithoutConfig_isUsageError() {
        assertThatThrownBy(
                        () -> DockerComposeSetupBootstrap.parse(new String[] {"--setup-docker-compose", "--dry-run"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--dry-run needs --config");
    }

    @Test
    void parse_noConfigLeavesInteractiveMode() {
        DockerComposeSetupBootstrap.parse(new String[] {"--setup-docker-compose"});

        assertThat(DockerComposeSetupBootstrap.configSource()).isNull();
        assertThat(DockerComposeSetupBootstrap.isDryRun()).isFalse();
    }

    @Test
    void parse_configWithoutModeFlag_staysInRemaining() {
        // No mode flag: the config flags belong to no bootstrap here — main
        // turns them into a usage error instead of leaking them to Spring.
        String[] remaining = DockerComposeSetupBootstrap.parse(new String[] {"--config", "-", "--dry-run"});

        assertThat(DockerComposeSetupBootstrap.isMode()).isFalse();
        assertThat(DockerComposeSetupBootstrap.configSource()).isNull();
        assertThat(remaining).containsExactly("--config", "-", "--dry-run");
    }
}
