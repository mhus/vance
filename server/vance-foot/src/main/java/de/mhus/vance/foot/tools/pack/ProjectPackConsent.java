package de.mhus.vance.foot.tools.pack;

import de.mhus.vance.foot.auth.VancePaths;
import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.PendingLinePrompt;
import java.nio.file.Path;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Gate in front of project-layer tool packs. A pack definition carries a
 * command line that foot spawns ({@code npx -y chrome-devtools-mcp@latest})
 * or an endpoint it hands tool traffic to — and a project-layer
 * definition is content of the working directory. For a cloned
 * repository that means: starting foot in it would run what its author
 * put there, before any sandbox gate applies (the sandbox governs
 * brain-driven {@code client_exec_run} calls, not pack materialisation).
 *
 * <p>So project packs ask once:
 * <pre>
 *   Project pack 'chrome' wants to run:
 *     npx -y chrome-devtools-mcp@latest
 *   [1] load once  [2] always for this project  [3] don't load
 * </pre>
 * "Always" is recorded in {@link TrustedPacksStore} — under the global
 * home, never in the project, so the repo can't approve itself.
 *
 * <p>Global-layer packs are never gated: they are in the user's own home
 * directory and got there by the user's hand.
 *
 * <p>Without an interactive terminal (headless, {@code --no-ui}, a
 * daemon) there is nobody to ask, and silently loading would be the
 * wrong default for the exact case the gate exists for: the answer is
 * "don't load", logged once per pack.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ProjectPackConsent {

    /**
     * Answer window. Matches the sandbox prompt's budget — long enough
     * for a human who just started foot, short enough that an
     * unattended terminal doesn't stall tool availability for minutes.
     */
    static final long PROMPT_TIMEOUT_MS = 25_000;

    /**
     * How long to wait for the REPL to come up before giving up on
     * asking. Packs load on a background thread started during
     * command start-up, so the first project pack can reach this gate
     * before {@code ChatRepl.run()} has attached the live region — a
     * plain {@code canAsk()} check would lose that race and deny.
     */
    static final long TERMINAL_WAIT_MS = 10_000;

    private static final long TERMINAL_POLL_MS = 100;

    private final VancePaths paths;
    private final TrustedPacksStore trustedPacks;
    private final PendingLinePrompt prompt;
    private final ChatTerminal terminal;

    /**
     * Set by {@code VanceFootCommand} before pack loading starts:
     * {@code true} only when this run will actually bring up the
     * interactive REPL. Default {@code false} so a daemon / one-shot /
     * headless run denies immediately instead of waiting for a terminal
     * that will never arrive.
     */
    private volatile boolean interactiveExpected = false;

    public void setInteractiveExpected(boolean expected) {
        this.interactiveExpected = expected;
    }

    /** Whether this pack may be materialised. */
    public boolean isAllowed(LoadedPack pack) {
        if (!pack.origin().isProject()) {
            return true;
        }
        Path projectDir = paths.projectLocalDir();
        Path home = paths.globalHomeDir();
        if (trustedPacks.isTrusted(home, projectDir, pack)) {
            log.debug("ProjectPackConsent: pack '{}' already trusted for {}",
                    pack.name(), projectDir);
            return true;
        }
        if (!awaitAskableTerminal()) {
            log.warn("ProjectPackConsent: skipping project pack '{}' ({}) — no interactive "
                            + "terminal to confirm '{}'",
                    pack.name(), pack.file(), pack.reachDescription());
            return false;
        }
        return ask(pack, home, projectDir);
    }

    /**
     * Waits (bounded) until the REPL can deliver an answer. Returns
     * immediately when no interactive run is expected, or as soon as the
     * prompt is answerable.
     */
    private boolean awaitAskableTerminal() {
        if (prompt.canAsk()) {
            return true;
        }
        if (!interactiveExpected) {
            return false;
        }
        long deadline = System.nanoTime() + TERMINAL_WAIT_MS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            try {
                Thread.sleep(TERMINAL_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (prompt.canAsk()) {
                return true;
            }
        }
        // An interactive run was expected but the live region never
        // attached — a dumb terminal, most likely. Deny like any other
        // unanswerable case.
        return false;
    }

    private boolean ask(LoadedPack pack, Path home, Path projectDir) {
        terminal.warn("Project tool pack '" + pack.name() + "' from " + pack.file());
        terminal.warn("  wants to run: " + pack.reachDescription());
        String answer = prompt.ask(
                "Load it? [1] once  [2] always for this project  [3] no › ",
                false,
                PROMPT_TIMEOUT_MS);
        String trimmed = answer == null ? "" : answer.trim();
        switch (trimmed) {
            case "1" -> {
                terminal.info("Loading '" + pack.name() + "' for this run.");
                return true;
            }
            case "2" -> {
                trustedPacks.trust(home, projectDir, pack);
                terminal.info("Trusting '" + pack.name() + "' for " + projectDir
                        + " — recorded in " + trustedPacks.file(home) + ".");
                return true;
            }
            default -> {
                // Covers "3", an unparseable answer and the timeout: an
                // unclear answer must not load foreign code.
                terminal.info("Not loading '" + pack.name()
                        + "'. Move it to " + home.resolve(FootToolPackLoader.SUBDIR)
                        + " to load it unconditionally.");
                return false;
            }
        }
    }
}
