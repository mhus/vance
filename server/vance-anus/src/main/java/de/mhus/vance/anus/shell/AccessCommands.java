package de.mhus.vance.anus.shell;

import de.mhus.vance.anus.access.AccessService;
import java.time.Duration;
import org.apache.commons.lang3.StringUtils;
import org.jline.reader.LineReader;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

/**
 * Login / logout / status / hash. None of these need {@code @RequiresAuth}:
 * {@code login} is the gate itself, {@code logout} is a no-op when nothing is
 * armed, {@code status} just reports state, and {@code hash} is a stateless
 * helper for ops to mint a fresh BCrypt hash for the env var.
 */
@Component
public class AccessCommands {

    private final AccessService accessService;
    // ObjectProvider breaks the eager wiring cycle: Spring Shell's LineReader
    // depends (transitively) on the command catalog, which in turn must
    // contain THIS bean. Resolving it lazily at first use side-steps the
    // cycle that Spring Boot 4 rejects.
    private final ObjectProvider<LineReader> lineReader;

    public AccessCommands(AccessService accessService, ObjectProvider<LineReader> lineReader) {
        this.accessService = accessService;
        this.lineReader = lineReader;
    }

    @Command(name = "login", description = "Authenticate. Without --password the prompt is masked.")
    public String login(
            @Option(longName = "password", shortName = 'p',
                    description = "Plaintext password — usually omitted; pass only in trusted scripts.")
            @Nullable String password) {
        String plain = password;
        if (StringUtils.isBlank(plain)) {
            // JLine masks the input with the given char and never echoes it
            // back to the terminal. Empty mask char (0) would print nothing
            // at all; '*' gives a length cue without revealing the value.
            plain = lineReader.getObject().readLine("Password: ", '*');
        }
        if (accessService.login(plain)) {
            return "Authorized.";
        }
        return "Login failed.";
    }

    @Command(name = "logout", description = "Drop the current authorisation.")
    public String logout() {
        accessService.logout();
        return "Logged out.";
    }

    @Command(name = "status", description = "Show current authorisation state.")
    public String status() {
        StringBuilder sb = new StringBuilder();
        if (accessService.isAuthorized()) {
            sb.append("Authorized. Expires in ")
                    .append(formatDuration(accessService.remaining()))
                    .append('.');
        } else {
            sb.append("Not authorized.");
        }
        if (accessService.isLoginDisabledWarning()) {
            sb.append("\nWARNING: no login credential configured — login is DISABLED. "
                    + "Set VANCE_ANUS_PASSWORD_HASH ({noop}<password> or a {bcrypt} hash).");
        }
        return sb.toString();
    }

    @Command(name = "hash",
            description = "Mint a {bcrypt} credential from a plaintext password (for VANCE_ANUS_PASSWORD_HASH). "
                    + "For zero-friction setups you can also just use {noop}<password> directly.")
    public String hash(
            @Option(longName = "plain",
                    description = "Plaintext password. Omit to be prompted with a masked input.")
            @Nullable String plain) {
        String input = plain;
        if (StringUtils.isBlank(input)) {
            input = lineReader.getObject().readLine("Password: ", '*');
        }
        if (StringUtils.isBlank(input)) {
            return "Empty password — refusing.";
        }
        // Strength 12: same default we'd want for the production env var.
        // Each call generates a fresh salt, so the hash differs every time.
        // The {bcrypt} prefix matches the DelegatingPasswordEncoder convention
        // AccessService uses, so the output can be pasted straight into the
        // VANCE_ANUS_PASSWORD_HASH secret.
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
        return "{bcrypt}" + encoder.encode(input);
    }

    private static String formatDuration(Duration d) {
        long total = d.getSeconds();
        long m = total / 60;
        long s = total % 60;
        if (m == 0) {
            return s + "s";
        }
        return m + "m " + s + "s";
    }
}
