package de.mhus.vance.foot.permission;

import de.mhus.vance.api.ws.RemoteClientPrompt;
import de.mhus.vance.api.ws.RemoteClientPromptOption;
import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.remote.RemoteClientIdentity;
import de.mhus.vance.foot.remote.RemoteWatcherState;
import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.ColorResolver;
import de.mhus.vance.foot.ui.LiveRegion;
import de.mhus.vance.foot.ui.Verbosity;
import java.util.List;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jline.utils.AttributedStyle;
import org.jspecify.annotations.Nullable;
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
    private final ColorResolver colorResolver;
    private final RemoteWatcherState watchers;
    private final RemoteClientIdentity identity;
    private final FootConfig config;

    public PermissionPrompt(PendingPermissionPrompt pending,
                            ChatTerminal terminal,
                            @Lazy LiveRegion liveRegion,
                            PermissionConfigLoader loader,
                            PermissionService permissions,
                            ColorResolver colorResolver,
                            RemoteWatcherState watchers,
                            RemoteClientIdentity identity,
                            FootConfig config) {
        this.pending = pending;
        this.terminal = terminal;
        this.liveRegion = liveRegion;
        this.loader = loader;
        this.permissions = permissions;
        this.colorResolver = colorResolver;
        this.watchers = watchers;
        this.identity = identity;
        this.config = config;
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
        // A remote watcher is an answering surface too: it feeds lines through
        // the same input path the REPL does. Without this the whole point of
        // remote control would be lost exactly when it matters — a foot run
        // without a live region could never be unblocked from the road.
        boolean remote = watchers.hasWatchers();
        if (!liveRegion.isAttached() && !remote) {
            log.warn("permission DENY (no interactive REPL attached): tool='{}' {}",
                    toolName, subject);
            return PermissionDecision.DENY;
        }

        // The 25 s default is tuned for a human at the keyboard. With somebody
        // watching from elsewhere it would deny before the notification is even
        // read, so the window follows where the answer can come from.
        long timeoutMs = remote
                ? Math.max(TIMEOUT_MS, config.getRemote().getPromptTimeout().toMillis())
                : TIMEOUT_MS;

        publishPrompt(toolName, domain, subject, true, timeoutMs);
        PermissionChoice choice;
        try {
            choice = pending.await(() -> printMenu(toolName, domain, subject), timeoutMs);
        } finally {
            publishPrompt(toolName, domain, subject, false, 0);
        }
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

    /**
     * Mirrors the menu to attached remote watchers so a phone can render
     * buttons. Each option carries the literal line it submits — the remote
     * answer then travels the exact path a typed answer would, and there is no
     * second answer protocol to keep in sync with this menu.
     */
    private void publishPrompt(String toolName, PermissionDomain domain, String subject,
                               boolean open, long timeoutMs) {
        watchers.publishPrompt(RemoteClientPrompt.builder()
                .clientId(identity.clientId())
                .kind("permission")
                .open(open)
                .question("Permission required: " + toolName)
                .subject(domainLabel(domain) + ": " + subject)
                .options(List.of(
                        option("allow once", "1"),
                        option("allow always", "2"),
                        option("deny once", "3"),
                        option("deny always", "4")))
                .timeoutMs(timeoutMs)
                .build());
    }

    private static RemoteClientPromptOption option(String label, String value) {
        return RemoteClientPromptOption.builder().label(label).value(value).build();
    }

    private static String domainLabel(PermissionDomain domain) {
        return switch (domain) {
            case COMMANDS -> "command";
            case DELETE -> "delete path";
            case PATHS -> "path";
        };
    }

    private void printMenu(String toolName, PermissionDomain domain, String subject) {
        // The DELETE label spells out what an "always" answer widens, so
        // the user does not read it as a general path grant.
        String label = switch (domain) {
            case COMMANDS -> "command";
            case DELETE -> "delete path";
            case PATHS -> "path";
        };
        AttributedStyle warnStyle = colorResolver.permissionWarn();
        if (warnStyle == null) warnStyle = AttributedStyle.DEFAULT;
        terminal.printBoxed(
                Verbosity.WARN,
                warnStyle,
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
