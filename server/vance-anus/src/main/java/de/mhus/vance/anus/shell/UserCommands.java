package de.mhus.vance.anus.shell;

import de.mhus.vance.anus.access.RequiresAuth;
import de.mhus.vance.shared.password.PasswordPolicyException;
import de.mhus.vance.shared.password.PasswordPolicyService;
import de.mhus.vance.shared.password.PasswordService;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.maintenance.UserDataHandler;
import de.mhus.vance.anus.maintenance.UserMaintenanceService;
import de.mhus.vance.shared.user.UserService;
import de.mhus.vance.shared.user.UserStatus;
import java.util.List;
import java.util.function.Function;
import org.apache.commons.lang3.StringUtils;
import org.jline.reader.LineReader;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

/**
 * CRUD over {@link UserDocument}. Passwords are hashed in this layer (Anus is
 * the operator UI; brain users hand it a plain password) and only the hash is
 * persisted via {@link UserService#setPasswordHash(String, String, String)}.
 * Hashing goes through the shared {@link PasswordService} so the BCrypt cost
 * stays uniform with the brain login path, and the plaintext is checked
 * against {@link PasswordPolicyService} first.
 */
@Component
@RequiresAuth
public class UserCommands {

    private final UserService userService;
    private final PasswordService passwordService;
    private final PasswordPolicyService passwordPolicyService;
    private final UserMaintenanceService maintenanceService;
    // Lazy LineReader to avoid the Spring-Shell bean cycle — see AccessCommands.
    private final ObjectProvider<LineReader> lineReader;

    public UserCommands(
            UserService userService,
            PasswordService passwordService,
            PasswordPolicyService passwordPolicyService,
            UserMaintenanceService maintenanceService,
            ObjectProvider<LineReader> lineReader) {
        this.userService = userService;
        this.passwordService = passwordService;
        this.passwordPolicyService = passwordPolicyService;
        this.maintenanceService = maintenanceService;
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
            try {
                passwordPolicyService.validate(plain);
            } catch (PasswordPolicyException e) {
                return "Password rejected: " + e.getMessage();
            }
            hash = passwordService.hash(plain);
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
        try {
            passwordPolicyService.validate(plain);
        } catch (PasswordPolicyException e) {
            return "Password rejected: " + e.getMessage();
        }
        String hash = passwordService.hash(plain);
        userService.setPasswordHash(tenant, name, hash);
        return "Password reset for user '" + name + "' in tenant '" + tenant + "'.";
    }

    // ─── Service tasks across every entity ─────────────────────────────────
    //
    // Through UserMaintenanceService, which asks one UserDataHandler per
    // entity. What "delete" means differs per entity and is the handler's
    // decision — the account's own data goes, its authority goes, and what it
    // did is tombstoned to `_deleted_<name>` so a future account under the same
    // login does not inherit somebody else's history.

    @Command(name = {"user", "inspect"},
            description = "Count everything an account touched, per entity. Writes nothing.")
    public String inspect(
            @Option(longName = "tenant", shortName = 'T', required = true) String tenant,
            @Option(longName = "name", shortName = 'n', required = true) String name) {
        try {
            return MaintenanceOutput.render(maintenanceService.inspect(tenant, name), "user");
        } catch (RuntimeException e) {
            return "Inspect FAILED — " + e.getMessage();
        }
    }

    @Command(name = {"user", "handlers"},
            description = "List the entities this process can delete or rename for a user.")
    public String handlers() {
        return Tables.render(
                List.of("ORDER", "ENTITY", "COLLECTIONS"),
                List.<Function<UserDataHandler, @Nullable Object>>of(
                        UserDataHandler::order,
                        UserDataHandler::id,
                        h -> String.join(",", h.collections())),
                maintenanceService.handlers());
    }

    @Command(name = {"user", "delete"},
            description = "Hard-delete an account and everything it owns. Irreversible.")
    public String delete(
            @Option(longName = "tenant", shortName = 'T', required = true) String tenant,
            @Option(longName = "name", shortName = 'n', required = true) String name,
            @Option(longName = "confirm",
                    description = "Type the user name here to confirm. Required when there is"
                            + " no terminal to ask on (--sudo).")
            @Nullable String confirm,
            @Option(longName = "force",
                    description = "Proceed even though something still runs as this account.",
                    defaultValue = "false")
            boolean force) {
        String problem = confirmed(name, confirm, "delete");
        if (problem != null) {
            return problem;
        }
        try {
            return MaintenanceOutput.render(
                    maintenanceService.delete(tenant, name, force), "user");
        } catch (UserMaintenanceService.UserInUseException e) {
            return "Refusing to delete — the account is in use:\n  "
                    + String.join("\n  ", e.blockers())
                    + "\n\nUse --force if that is known to be stale.";
        } catch (RuntimeException e) {
            return "Delete FAILED — " + e.getMessage();
        }
    }

    @Command(name = {"user", "rename"},
            description = "Rename an account, carrying everything it owns and did with it.")
    public String rename(
            @Option(longName = "tenant", shortName = 'T', required = true) String tenant,
            @Option(longName = "name", shortName = 'n', required = true) String name,
            @Option(longName = "to", required = true,
                    description = "The new login.") String to,
            @Option(longName = "confirm",
                    description = "Type the current user name here to confirm.")
            @Nullable String confirm) {
        String problem = confirmed(name, confirm, "rename");
        if (problem != null) {
            return problem;
        }
        try {
            return MaintenanceOutput.render(maintenanceService.rename(tenant, name, to), "user")
                    + "\n\nReferences inside document content (a login named in a scheduler's"
                    + "\nrunAs, a prompt, a recipe) are NOT rewritten — search for '" + name
                    + "'.";
        } catch (UserMaintenanceService.RenameBlockedException e) {
            return "Rename FAILED — nothing was written:\n  "
                    + String.join("\n  ", e.blockers());
        } catch (RuntimeException e) {
            return "Rename FAILED — " + e.getMessage();
        }
    }

    /**
     * The typed confirmation, or the reason it did not happen. Same gate as on
     * the project side: the point is to make the hand pause on the
     * <em>right</em> account, which a yes/no prompt does not do.
     */
    private @Nullable String confirmed(String name, @Nullable String confirm, String operation) {
        String answer = confirm;
        if (StringUtils.isBlank(answer)) {
            LineReader reader = lineReader.getIfAvailable();
            if (reader == null) {
                return "Refusing to " + operation + " without confirmation — pass --confirm "
                        + name;
            }
            answer = reader.readLine(
                    "Type the user name '" + name + "' to confirm the " + operation + ": ");
        }
        if (!name.equals(answer == null ? null : answer.trim())) {
            return "Confirmation did not match '" + name + "' — nothing was done.";
        }
        return null;
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
