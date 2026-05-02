package de.mhus.vance.foot.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import de.mhus.vance.foot.ui.ChatTerminal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for the slash-command dispatcher. Covers the parser path,
 * unknown-command handling, exception swallowing, and duplicate-name
 * fail-fast on construction.
 */
class CommandServiceTest {

    @Test
    void execute_dispatchesToMatchingCommand_withSplitArgs() {
        FakeCommand foo = new FakeCommand("foo");
        CommandService svc = new CommandService(List.of(foo), mock(ChatTerminal.class));

        boolean handled = svc.execute("/foo arg1 arg2");

        assertThat(handled).isTrue();
        assertThat(foo.lastArgs).containsExactly("arg1", "arg2");
    }

    @Test
    void execute_isCaseInsensitive_onCommandName() {
        FakeCommand foo = new FakeCommand("foo");
        CommandService svc = new CommandService(List.of(foo), mock(ChatTerminal.class));

        assertThat(svc.execute("/FOO")).isTrue();
        assertThat(svc.execute("/Foo")).isTrue();
        assertThat(foo.invocations).isEqualTo(2);
    }

    @Test
    void execute_collapsesWhitespaceBetweenTokens() {
        FakeCommand foo = new FakeCommand("foo");
        CommandService svc = new CommandService(List.of(foo), mock(ChatTerminal.class));

        svc.execute("/foo   one\ttwo  three");

        assertThat(foo.lastArgs).containsExactly("one", "two", "three");
    }

    @Test
    void execute_returnsFalse_forNonSlashLine() {
        FakeCommand foo = new FakeCommand("foo");
        CommandService svc = new CommandService(List.of(foo), mock(ChatTerminal.class));

        assertThat(svc.execute("foo")).isFalse();
        assertThat(svc.execute("hello world")).isFalse();
        assertThat(foo.invocations).isZero();
    }

    @Test
    void execute_returnsFalse_andReportsError_forUnknownCommand() {
        ChatTerminal terminal = mock(ChatTerminal.class);
        CommandService svc = new CommandService(List.of(new FakeCommand("foo")), terminal);

        boolean handled = svc.execute("/missing");

        assertThat(handled).isFalse();
        verify(terminal, atLeast(1)).error(org.mockito.ArgumentMatchers.contains("missing"));
    }

    @Test
    void execute_swallowsCommandException_andReportsToTerminal() {
        ChatTerminal terminal = mock(ChatTerminal.class);
        FakeCommand boom = new FakeCommand("boom") {
            @Override public void execute(List<String> args) {
                throw new IllegalStateException("kaboom");
            }
        };
        CommandService svc = new CommandService(List.of(boom), terminal);

        // Must not propagate — REPL stays alive on command failure.
        boolean handled = svc.execute("/boom");

        assertThat(handled).isTrue();
        verify(terminal).error(org.mockito.ArgumentMatchers.contains("kaboom"));
    }

    @Test
    void constructor_failsFast_onDuplicateCommandName() {
        FakeCommand a = new FakeCommand("dup");
        FakeCommand b = new FakeCommand("dup");

        assertThatThrownBy(() ->
                new CommandService(List.of(a, b), mock(ChatTerminal.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dup");
    }

    @Test
    void find_returnsRegisteredCommand_orNull() {
        FakeCommand foo = new FakeCommand("foo");
        CommandService svc = new CommandService(List.of(foo), mock(ChatTerminal.class));

        assertThat(svc.find("foo")).isSameAs(foo);
        assertThat(svc.find("FOO")).isSameAs(foo); // lookup is case-insensitive
        assertThat(svc.find("missing")).isNull();
        assertThat(svc.find(null)).isNull();
        assertThat(svc.find("")).isNull();
    }

    @Test
    void all_returnsCommandsSortedByName() {
        CommandService svc = new CommandService(
                List.of(new FakeCommand("zebra"),
                        new FakeCommand("alpha"),
                        new FakeCommand("mike")),
                mock(ChatTerminal.class));

        assertThat(svc.all().stream().map(SlashCommand::name).toList())
                .containsExactly("alpha", "mike", "zebra");
    }

    private static class FakeCommand implements SlashCommand {
        private final String name;
        List<String> lastArgs = List.of();
        int invocations = 0;

        FakeCommand(String name) {
            this.name = name;
        }

        @Override public String name() { return name; }
        @Override public String description() { return "fake " + name; }
        @Override public void execute(List<String> args) {
            invocations++;
            lastArgs = new ArrayList<>(args);
        }
    }
}
