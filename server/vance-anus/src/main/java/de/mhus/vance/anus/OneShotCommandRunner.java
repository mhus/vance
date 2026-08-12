package de.mhus.vance.anus;

import de.mhus.vance.anus.access.AccessService;
import de.mhus.vance.anus.setup.SetupBootstrap;
import de.mhus.vance.anus.setup.SetupWizard;
import de.mhus.vance.anus.sudo.SudoBootstrap;
import java.io.PrintWriter;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.shell.core.NonInteractiveShellRunner;
import org.springframework.shell.core.command.CommandParser;
import org.springframework.shell.core.command.CommandRegistry;
import org.springframework.stereotype.Component;

/**
 * Handles the {@code --sudo} and {@code --setup} one-shot boot modes.
 *
 * <p>Spring Shell 4 dropped the 3.x dispatch model (a list of
 * {@code ShellRunner}s each asked {@code canRun}); there is now a single
 * framework {@code ShellRunner}, started by the {@code springShellApplicationRunner}
 * {@link ApplicationRunner} bean, that owns the interactive/non-interactive
 * choice. Rather than compete with that bean, anus is an
 * {@code ApplicationRunner} itself at {@link Ordered#HIGHEST_PRECEDENCE} —
 * the framework's runner carries no order, so it sorts last — and, in a
 * one-shot mode, does its work and calls {@link System#exit(int)}. That
 * short-circuits the REPL entirely; the Spring shutdown hook still closes the
 * context (audit flush et al.), so the exit stays clean.
 *
 * <p>It has to be a runner, not an {@code ApplicationReadyEvent} listener:
 * runners execute <em>before</em> that event is published, so the interactive
 * REPL would already own the terminal and block forever — {@code --setup}
 * would drop the caller at a {@code shell:>} prompt instead of the wizard.
 *
 * <p>Command execution reuses the framework's {@link NonInteractiveShellRunner}
 * (parse + execute a single line, throwing on a non-OK exit status) so anus
 * carries no bespoke command-dispatch code. It needs the anus commands to be
 * registered eagerly — {@code VanceAnusApplication} lists them via
 * {@code @EnableCommand} so they are in the {@link CommandRegistry} by the time
 * this runs (the framework's own annotation scan only registers them lazily
 * when the interactive shell starts, which is too late for a one-shot).
 *
 * <p>Precedence mirrors the old ordering: {@code --sudo} wins over
 * {@code --setup} when both are (oddly) present.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class OneShotCommandRunner implements ApplicationRunner {

    private final CommandParser commandParser;
    private final CommandRegistry commandRegistry;
    private final AccessService accessService;
    private final SetupWizard setupWizard;

    public OneShotCommandRunner(
            CommandParser commandParser,
            CommandRegistry commandRegistry,
            AccessService accessService,
            SetupWizard setupWizard) {
        this.commandParser = commandParser;
        this.commandRegistry = commandRegistry;
        this.accessService = accessService;
        this.setupWizard = setupWizard;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (SudoBootstrap.isSudoMode()) {
            System.exit(runSudo(SudoBootstrap.commands()));
        } else if (SetupBootstrap.isSetupMode()) {
            System.exit(runSetup());
        }
        // Otherwise fall through: the framework's ShellRunner takes over
        // (interactive REPL, or non-interactive if plain args were passed).
    }

    private int runSudo(List<String> commands) {
        log.info("Anus --sudo: executing {} command(s)", commands.size());
        accessService.armForSudo();
        // Autoflush writer: NonInteractiveShellRunner does not flush its own
        // output, and the System.exit above would drop a buffered command result.
        PrintWriter out = new PrintWriter(System.out, true);
        try {
            NonInteractiveShellRunner runner =
                    new NonInteractiveShellRunner(commandParser, commandRegistry, out);
            for (String line : commands) {
                // run() joins the array with spaces and parses the whole line,
                // so option flags survive; it throws on a non-OK exit status,
                // giving us abort-on-first-error for the calling script.
                runner.run(new String[] {line});
            }
            return 0;
        } catch (Exception e) {
            log.error("--sudo command failed: {}", e.toString());
            return 1;
        } finally {
            accessService.logout();
        }
    }

    private int runSetup() {
        log.info("Anus --setup: starting interactive setup wizard");
        accessService.armForSudo();
        try {
            setupWizard.run();
            return 0;
        } finally {
            accessService.logout();
        }
    }
}
