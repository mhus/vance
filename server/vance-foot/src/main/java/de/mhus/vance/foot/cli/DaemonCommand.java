package de.mhus.vance.foot.cli;

import de.mhus.vance.foot.agent.ClientAgentDocService;
import de.mhus.vance.foot.session.AutoBootstrapService;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code vance-foot daemon} — runs the Spring context without entering the
 * REPL. Used to keep the JVM alive while automation drives the CLI through
 * {@link de.mhus.vance.foot.debug.DebugRestServer}.
 *
 * <p>Spring's lifecycle plus the SIGTERM/SIGINT shutdown hook handle clean
 * exit; this command just blocks until the JVM is told to die.
 */
@Component
@Command(
        name = "daemon",
        mixinStandardHelpOptions = true,
        description = "Keep the Spring context alive without REPL — drive via /debug REST.")
public class DaemonCommand implements Callable<Integer> {

    @Option(names = "--no-bootstrap",
            description = "Skip the auto-bootstrap from vance.bootstrap config after welcome.")
    boolean noBootstrap;

    @Option(names = "--agent-file",
            paramLabel = "<path>",
            description = "Override the agent doc uploaded to the brain. "
                    + "Without this option the cascade is ./agent.md → ./CLAUDE.md.")
    @Nullable Path agentFile;

    private final ChatTerminal terminal;
    private final ClientAgentDocService agentDoc;

    public DaemonCommand(ChatTerminal terminal, ClientAgentDocService agentDoc) {
        this.terminal = terminal;
        this.agentDoc = agentDoc;
    }

    @Override
    public Integer call() throws InterruptedException {
        if (noBootstrap) {
            System.setProperty(AutoBootstrapService.SKIP_PROPERTY, "true");
        }
        if (agentFile != null) {
            agentDoc.setOverridePath(agentFile);
        }
        terminal.info("Daemon mode — Ctrl-C to exit, drive via /debug REST.");
        CountDownLatch park = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(park::countDown, "vance-foot-shutdown"));
        park.await();
        return 0;
    }
}
