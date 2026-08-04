package de.mhus.vance.brain.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class EngineCommandParseTest {

    @Test
    void parse_verbWithRemainder_carriesRemainderAsText() {
        EngineCommand cmd = EngineCommand.parse("echo hi there");

        assertThat(cmd.name()).isEqualTo("echo");
        assertThat(cmd.args()).isEqualTo(Map.of("text", "hi there"));
    }

    @Test
    void parse_verbOnly_hasEmptyArgs() {
        EngineCommand cmd = EngineCommand.parse("ping");

        assertThat(cmd.name()).isEqualTo("ping");
        assertThat(cmd.args()).isEmpty();
    }

    @Test
    void parse_stripsLeadingDoubleSlashAndTrims() {
        EngineCommand cmd = EngineCommand.parse("  //status coding  ");

        assertThat(cmd.name()).isEqualTo("status");
        assertThat(cmd.args()).isEqualTo(Map.of("text", "coding"));
    }

    @Test
    void parse_blankOrSlashOnly_throws() {
        assertThatThrownBy(() -> EngineCommand.parse(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EngineCommand.parse("//"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
