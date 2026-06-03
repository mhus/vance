package de.mhus.vance.anus.sudo;

import de.mhus.vance.anus.access.AccessService;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jline.reader.ParsedLine;
import org.jline.reader.Parser;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.ApplicationArguments;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.shell.Input;
import org.springframework.shell.InputProvider;
import org.springframework.shell.Shell;
import org.springframework.shell.ShellRunner;
import org.springframework.shell.context.InteractionMode;
import org.springframework.shell.context.ShellContext;
import org.springframework.stereotype.Component;

/**
 * Spring Shell {@link ShellRunner} that handles the {@code --sudo} one-shot
 * mode. Runs ahead of {@code ScriptShellRunner} (-100),
 * {@code NonInteractiveShellRunner} (-50) and {@code InteractiveShellRunner} (0)
 * via {@link Ordered#HIGHEST_PRECEDENCE}.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>{@link AccessService#armForSudo()} — arms the access window without a
 *       password, marks the service in sudo-mode so the default-password
 *       warning stays quiet, and writes an {@code anus.sudo.arm} audit row.
 *   <li>Pushes the shell context into {@link InteractionMode#NONINTERACTIVE}.
 *       This makes {@code Shell.run(InputProvider)} re-throw command failures
 *       instead of swallowing them — exactly the abort-on-first-error
 *       semantics we want.
 *   <li>Feeds the sudo command lines through a custom {@link InputProvider}
 *       that parses each line with the JLine parser (so quoted args work).
 *   <li>{@code finally}: {@link AccessService#logout()} drops the window
 *       even though the process exits right after — keeps the audit trail
 *       clean and stops a half-exited JVM from leaving an armed shell
 *       reachable to anything that might still pick up the bean.
 * </ol>
 *
 * <p>On exception, the {@link Shell} re-throws the original cause in
 * non-interactive mode. The exception then bubbles up through
 * {@code DefaultShellApplicationRunner} → {@code SpringApplication.run} →
 * {@code VanceAnusApplication.main}, which catches it and calls
 * {@code System.exit(1)}. That keeps the abort-on-first-error contract
 * visible to the calling script.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class SudoShellRunner implements ShellRunner {

    private final Shell shell;
    private final ShellContext shellContext;
    private final Parser lineParser;
    private final AccessService accessService;

    public SudoShellRunner(
            Shell shell,
            ShellContext shellContext,
            Parser lineParser,
            AccessService accessService) {
        this.shell = shell;
        this.shellContext = shellContext;
        this.lineParser = lineParser;
        this.accessService = accessService;
    }

    @Override
    public boolean canRun(ApplicationArguments args) {
        return SudoBootstrap.isSudoMode();
    }

    @Override
    public boolean run(String[] args) throws Exception {
        if (!SudoBootstrap.isSudoMode()) {
            // Hand off to the next runner (interactive or non-interactive).
            return false;
        }
        executeSudo();
        return true;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        executeSudo();
    }

    private void executeSudo() throws Exception {
        List<String> commands = SudoBootstrap.commands();
        log.info("Anus --sudo: executing {} command(s)", commands.size());
        accessService.armForSudo();
        try {
            shellContext.setInteractionMode(InteractionMode.NONINTERACTIVE);
            shell.run(new SudoInputProvider(commands, lineParser));
        } finally {
            accessService.logout();
        }
    }

    /**
     * Pulls command lines off a list, parses each through the JLine
     * {@link Parser}, and returns {@code null} when the list is exhausted —
     * which is the signal {@code Shell.run(InputProvider)} interprets as
     * end-of-input.
     */
    private static final class SudoInputProvider implements InputProvider {
        private final List<String> lines;
        private final Parser parser;
        private int idx;

        SudoInputProvider(List<String> lines, Parser parser) {
            this.lines = lines;
            this.parser = parser;
        }

        @Override
        public @Nullable Input readInput() {
            if (idx >= lines.size()) {
                return null;
            }
            String raw = lines.get(idx++);
            // +1 matches NonInteractiveShellRunner: positions the cursor
            // just past the last character so the parser treats the line
            // as complete.
            ParsedLine parsed = parser.parse(raw, raw.length() + 1);
            return new ParsedLineInput(parsed);
        }
    }

    private record ParsedLineInput(ParsedLine parsed) implements Input {
        @Override
        public String rawText() {
            return parsed.line();
        }

        @Override
        public List<String> words() {
            return parsed.words();
        }
    }
}
