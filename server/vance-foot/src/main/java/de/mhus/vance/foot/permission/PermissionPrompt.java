package de.mhus.vance.foot.permission;

import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.LiveRegion;
import de.mhus.vance.foot.ui.Verbosity;
import java.util.List;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jline.utils.AttributedStyle;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * Drives the interactive "may the brain do this?" prompt when a tool call
 * matches no allow/deny rule ({@link PermissionDecision#ASK}). Shows a
 * four-option menu in the REPL, waits for the answer via
 * {@link PendingPermissionPrompt}, persists an exact-match rule on the
 * "always" choices, and returns the final {@link PermissionDecision}.
 *
 * <p>When no interactive surface is available (headless / no REPL
 * attached) the prompt cannot be answered, so it denies immediately —
 * the same safe fallback as a timeout.
 */
@Service
@Slf4j
public class PermissionPrompt implements InteractivePermissionResolver {

    /** Menu timeout. Kept under the brain's ~30 s tool-invoke timeout. */
    private static final long TIMEOUT_MS = 25_000;

    private final PendingPermissionPrompt pending;
    private final ChatTerminal terminal;
    private final LiveRegion liveRegion;
    private final PermissionConfigLoader loader;
    private final PermissionService permissions;

    public PermissionPrompt(PendingPermissionPrompt pending,
                            ChatTerminal terminal,
                            @Lazy LiveRegion liveRegion,
                            PermissionConfigLoader loader,
                            PermissionService permissions) {
        this.pending = pending;
        this.terminal = terminal;
        this.liveRegion = liveRegion;
        this.loader = loader;
        this.permissions = permissions;
    }

    /**
     * Resolves an {@code ASK} verdict interactively. {@code subject} is the
     * canonical path (for {@link PermissionDomain#PATHS}) or the raw command
     * (for {@link PermissionDomain#COMMANDS}); it is shown to the user and,
     * on an "always" answer, turned into an exact-match rule.
     */
    @Override
    public PermissionDecision resolve(String toolName, PermissionDomain domain, String subject) {
        if (!permissions.isInteractive()) {
            log.warn("permission DENY (headless/daemon, no user to ask): tool='{}' {}",
                    toolName, subject);
            return PermissionDecision.DENY;
        }
        if (!liveRegion.isAttached()) {
            log.warn("permission DENY (no interactive REPL attached): tool='{}' {}",
                    toolName, subject);
            return PermissionDecision.DENY;
        }

        PermissionChoice choice = pending.await(() -> printMenu(toolName, domain, subject), TIMEOUT_MS);
        if (choice == null) {
            terminal.warn("⏲ permission prompt timed out — denied: " + toolName + " on " + subject);
            return PermissionDecision.DENY;
        }

        if (choice.isAlways()) {
            persist(domain, choice.isAllow(), subject);
        }
        if (choice.isAllow()) {
            terminal.info("✓ allowed: " + toolName + " on " + subject
                    + (choice.isAlways() ? " (saved)" : ""));
            return PermissionDecision.ALLOW;
        }
        terminal.info("✗ denied: " + toolName + " on " + subject
                + (choice.isAlways() ? " (saved)" : ""));
        return PermissionDecision.DENY;
    }

    private void printMenu(String toolName, PermissionDomain domain, String subject) {
        String label = domain == PermissionDomain.COMMANDS ? "command" : "path";
        terminal.printBoxed(
                Verbosity.WARN,
                AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW).bold(),
                List.of(
                        "🔒 Permission required: " + toolName,
                        "   " + label + ": " + subject,
                        "   [1] allow once   [2] allow always   "
                                + "[3] deny once   [4] deny always"));
    }

    /** Builds and stores an exact-match rule, then refreshes the live policy. */
    private void persist(PermissionDomain domain, boolean allow, String subject) {
        String rule = domain == PermissionDomain.COMMANDS
                ? "^" + Pattern.quote(subject) + "$"
                : subject; // canonical path, matched literally as a glob
        loader.appendRule(domain, allow, rule);
        permissions.reload();
    }
}
