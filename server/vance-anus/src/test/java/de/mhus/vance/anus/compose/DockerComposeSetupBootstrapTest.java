package de.mhus.vance.anus.compose;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DockerComposeSetupBootstrapTest {

    @AfterEach
    void tearDown() {
        DockerComposeSetupBootstrap.reset();
    }

    @Test
    void parse_stripsFlagAndEnablesMode() {
        String[] remaining = DockerComposeSetupBootstrap.parse(
                new String[] {"foo", "--setup-docker-compose", "bar"});

        assertThat(remaining).containsExactly("foo", "bar");
        assertThat(DockerComposeSetupBootstrap.isMode()).isTrue();
    }

    @Test
    void parse_withoutFlag_leavesModeOff() {
        String[] remaining = DockerComposeSetupBootstrap.parse(new String[] {"chat"});

        assertThat(remaining).containsExactly("chat");
        assertThat(DockerComposeSetupBootstrap.isMode()).isFalse();
    }
}
