package de.mhus.vance.anus.sudo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SudoBootstrapTest {

    @BeforeEach
    @AfterEach
    void resetStaticState() {
        SudoBootstrap.reset();
    }

    @Test
    void parse_withoutSudoFlag_leavesArgsUntouchedAndSudoModeOff() {
        String[] remaining = SudoBootstrap.parse(new String[] {"--spring.profiles.active=dev"});

        assertThat(remaining).containsExactly("--spring.profiles.active=dev");
        assertThat(SudoBootstrap.isSudoMode()).isFalse();
        assertThat(SudoBootstrap.commands()).isEmpty();
    }

    @Test
    void parse_singleSudoFlag_extractsCommandAndStripsArgs() {
        String[] remaining = SudoBootstrap.parse(new String[] {"--sudo", "tenant list"});

        assertThat(remaining).isEmpty();
        assertThat(SudoBootstrap.isSudoMode()).isTrue();
        assertThat(SudoBootstrap.commands()).containsExactly("tenant list");
    }

    @Test
    void parse_multipleSudoFlags_preservesOrder() {
        String[] remaining = SudoBootstrap.parse(new String[] {
                "--sudo", "tenant create --name acme",
                "--sudo", "user create --name alice"
        });

        assertThat(remaining).isEmpty();
        assertThat(SudoBootstrap.commands()).containsExactly(
                "tenant create --name acme",
                "user create --name alice");
    }

    @Test
    void parse_equalsForm_acceptedAlongsideSpaceForm() {
        String[] remaining = SudoBootstrap.parse(new String[] {
                "--sudo=tenant list",
                "--sudo", "user list"
        });

        assertThat(remaining).isEmpty();
        assertThat(SudoBootstrap.commands()).containsExactly("tenant list", "user list");
    }

    @Test
    void parse_mixedWithSpringArgs_keepsSpringArgsAndExtractsSudo() {
        String[] remaining = SudoBootstrap.parse(new String[] {
                "--spring.profiles.active=dev",
                "--sudo", "tenant list",
                "--vance.anus.access.timeout=10m"
        });

        assertThat(remaining).containsExactly(
                "--spring.profiles.active=dev",
                "--vance.anus.access.timeout=10m");
        assertThat(SudoBootstrap.commands()).containsExactly("tenant list");
    }

    @Test
    void parse_bareSudoFlagWithoutArg_rejected() {
        assertThatThrownBy(() -> SudoBootstrap.parse(new String[] {"--sudo"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--sudo");
    }

    @Test
    void parse_blankCommand_rejected() {
        assertThatThrownBy(() -> SudoBootstrap.parse(new String[] {"--sudo", "   "}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SudoBootstrap.parse(new String[] {"--sudo="}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void commands_immutable() {
        SudoBootstrap.parse(new String[] {"--sudo", "tenant list"});

        assertThatThrownBy(() -> SudoBootstrap.commands().add("evil"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
