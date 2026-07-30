package de.mhus.vance.anus.shell;

import de.mhus.vance.anus.access.RequiresAuth;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserService;
import de.mhus.vance.shared.user.UserStatus;
import java.util.List;
import java.util.function.Function;
import org.apache.commons.lang3.StringUtils;
import org.jline.reader.LineReader;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

/**
 * CRUD over {@link UserDocument}. Passwords are hashed in this layer (Anus is
 * the operator UI; brain users hand it a plain password) and only the hash is
 * persisted via {@link UserService#setPasswordHash(String, String, String)}.
 */
@Component
@RequiresAuth
public class UserCommands {

    private final UserService userService;
    // Lazy LineReader to avoid the Spring-Shell bean cycle — see AccessCommands.
    private final ObjectProvider<LineReader> lineReader;

    public UserCommands(UserService userService, ObjectProvider<LineReader> lineReader) {
        this.userService = userService;
        this.lineReader = lineReader;
    }

    @Command(name = {"user", "list"}, description = "List users in a tenant.")
    public String list(@Option(longName = "tenant", shortName = 'T', required = true) String tenant) {
        List<UserDocument> all = userService.all(tenant);
        if (all.isEmpty()) {
            return "(no users in tenant '" + tenant + "')";
        }
        return Tables.render(
                List.of("NAME", "TITLE", "EMAIL", "STATUS", "LOGIN", "TYPE"),
                List.<Function<UserDocument, @Nullable Object>>of(
                        UserDocument::getName,
                        UserDocument::getTitle,
                        UserDocument::getEmail,
                        UserDocument::getStatus,
                        UserDocument::isLoginEnabled,
                        u -> u.isServiceAccount() ? "service" : "user"),
                all);
    }

    @Command(name = {"user", "show"}, description = "Show a user.")
    public String show(
            @Option(longName = "tenant", shortName = 'T', required = true) String tenant,
            @Option(longName = "name", shortName = 'n', required = true) String name) {
        return userService.findByTenantAndName(tenant, name)
                .map(UserCommands::renderOne)
                .orElse("User '" + name + "' not found in tenant '" + tenant + "'.");
    }

    @Command(name = {"user", "create"}, description = "Create a user. Password is prompted (masked) when --password is omitted.")
    public String create(
            @Option(longName = "tenant", shortName = 'T', required = true) String tenant,
            @Option(longName = "name", shortName = 'n', required = true) String name,
            @Option(longName = "title", shortName = 't') @Nullable String title,
            @Option(longName = "email", shortName = 'e') @Nullable String email,
            @Option(longName = "password", shortName = 'p',
                    description = "Plaintext. Stored as BCrypt hash. Omit to be prompted with masked input.")
            @Nullable String password,
            @Option(longName = "no-password",
                    description = "Create the user without setting a password (e.g. for SSO-only accounts).",
                    defaultValue = "false")
            boolean noPassword,
            @Option(longName = "service-account",
                    description = "Mark as service account. Name must start with '_' and not with '_vance-'. "
                            + "Login is disabled by default; tokens must be minted out-of-band.",
                    defaultValue = "false")
            boolean serviceAccount) {
        @Nullable String hash = null;
        if (!noPassword) {
            String plain = StringUtils.isBlank(password)
                    ? lineReader.getObject().readLine("Password for '" + name + "': ", '*')
                    : password;
            if (StringUtils.isBlank(plain)) {
                return "Empty password — refusing. Use --no-password to create without one.";
            }
            hash = new BCryptPasswordEncoder(12).encode(plain);
        }
        UserDocument user = serviceAccount
                ? userService.createServiceAccount(tenant, name, hash, title, email)
                : userService.create(tenant, name, hash, title, email);
        return "Created:\n" + renderOne(user);
    }

    @Command(name = {"user", "update"}, description = "Update mutable fields of a user.")
    public String update(
            @Option(longName = "tenant", shortName = 'T', required = true) String tenant,
            @Option(longName = "name", shortName = 'n', required = true) String name,
            @Option(longName = "title", shortName = 't') @Nullable String title,
            @Option(longName = "email", shortName = 'e') @Nullable String email,
            @Option(longName = "status", shortName = 's',
                    description = "ACTIVE | DISABLED")
            @Nullable UserStatus status,
            @Option(longName = "login-enabled",
                    description = "Toggle the password-login gate. Cannot be set to true on service accounts.")
            @Nullable Boolean loginEnabled) {
        UserDocument user = userService.update(tenant, name, title, email, status, loginEnabled);
        return "Updated:\n" + renderOne(user);
    }

    @Command(name = {"user", "set-password"},
            description = "Reset a user's password. Plaintext prompted (masked) when --password is omitted.")
    public String setPassword(
            @Option(longName = "tenant", shortName = 'T', required = true) String tenant,
            @Option(longName = "name", shortName = 'n', required = true) String name,
            @Option(longName = "password", shortName = 'p') @Nullable String password) {
        String plain = StringUtils.isBlank(password)
                ? lineReader.getObject().readLine("New password for '" + name + "': ", '*')
                : password;
        if (StringUtils.isBlank(plain)) {
            return "Empty password — refusing.";
        }
        String hash = new BCryptPasswordEncoder(12).encode(plain);
        userService.setPasswordHash(tenant, name, hash);
        return "Password reset for user '" + name + "' in tenant '" + tenant + "'.";
    }

    @Command(name = {"user", "delete"}, description = "Hard-delete a user.")
    public String delete(
            @Option(longName = "tenant", shortName = 'T', required = true) String tenant,
            @Option(longName = "name", shortName = 'n', required = true) String name) {
        userService.delete(tenant, name);
        return "Deleted user '" + name + "' in tenant '" + tenant + "'.";
    }

    private static String renderOne(UserDocument u) {
        return "  tenantId    : " + u.getTenantId() + "\n"
                + "  name        : " + u.getName() + "\n"
                + "  title       : " + (u.getTitle() == null ? "" : u.getTitle()) + "\n"
                + "  email       : " + (u.getEmail() == null ? "" : u.getEmail()) + "\n"
                + "  status      : " + u.getStatus() + "\n"
                + "  loginEnabled: " + u.isLoginEnabled() + "\n"
                + "  serviceAcct : " + u.isServiceAccount() + "\n"
                + "  hasHash     : " + (u.getPasswordHash() != null) + "\n"
                + "  created     : " + (u.getCreatedAt() == null ? "" : u.getCreatedAt()) + "\n"
                + "  id          : " + (u.getId() == null ? "" : u.getId());
    }
}
