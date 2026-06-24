package de.mhus.vance.anus.setup;

import de.mhus.vance.anus.access.AccessService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.shell.ShellRunner;
import org.springframework.shell.context.InteractionMode;
import org.springframework.shell.context.ShellContext;
import org.springframework.stereotype.Component;

/**
 * Spring Shell {@link ShellRunner} that handles the {@code --setup} one-shot
 * wizard mode. Ordered between {@code SudoShellRunner}
 * ({@link Ordered#HIGHEST_PRECEDENCE}) and the default interactive runner
 * (0) — {@code --sudo} stays the higher-priority short-circuit so that
 * {@code --sudo} + {@code --setup} (an odd combination) deterministically
 * runs sudo and skips the wizard.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>{@link AccessService#armForSudo()} — same trick as the sudo runner.
 *       Setup runs unattended-on-purpose: the caller has already proven it
 *       can launch the process, no further credential gate is meaningful.
 *       The audit row {@code anus.sudo.arm} also marks the setup boot.
 *   <li>Pushes the shell context into {@link InteractionMode#NONINTERACTIVE}
 *       so the JLine prompt does not engage on top of the wizard.
 *   <li>Delegates to {@link SetupWizard#run()}, which drives stdin/stdout.
 *   <li>{@code finally}: {@link AccessService#logout()} drops the window.
 * </ol>
 *
 * <p>Returns {@code true} to signal Spring Shell that boot is finished;
 * {@code SpringApplication.exit} then closes the context and the JVM
 * exits with code 0 (or the wizard's chosen exit code via
 * {@link System#exit(int)}).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@Slf4j
public class SetupShellRunner implements ShellRunner {

    private final ShellContext shellContext;
    private final AccessService accessService;
    private final SetupWizard wizard;

    public SetupShellRunner(
            ShellContext shellContext,
            AccessService accessService,
            SetupWizard wizard) {
        this.shellContext = shellContext;
        this.accessService = accessService;
        this.wizard = wizard;
    }

    @Override
    public boolean canRun(ApplicationArguments args) {
        return SetupBootstrap.isSetupMode();
    }

    @Override
    public boolean run(String[] args) throws Exception {
        if (!SetupBootstrap.isSetupMode()) {
            return false;
        }
        executeSetup();
        return true;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        executeSetup();
    }

    private void executeSetup() {
        log.info("Anus --setup: starting interactive setup wizard");
        accessService.armForSudo();
        try {
            shellContext.setInteractionMode(InteractionMode.NONINTERACTIVE);
            wizard.run();
        } finally {
            accessService.logout();
        }
    }
}
