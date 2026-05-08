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
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

/**
 * CRUD over {@link UserDocument}. Passwords are hashed in this layer (Anus is
 * the operator UI; brain users hand it a plain password) and only the hash is
 * persisted via {@link UserService#setPasswordHash(String, String, String)}.
 */
@ShellComponent
@RequiresAuth
public class UserCommands {

    private final UserService userService;
    // Lazy LineReader to avoid the Spring-Shell bean cycle — see AccessCommands.
    private final ObjectProvider<LineReader> lineReader;

    public UserCommands(UserService userService, ObjectProvider<LineReader> lineReader) {
        this.userService = userService;
        this.lineReader = lineReader;
    }

    @ShellMethod(key = "user list", value = "List users in a tenant.")
    public String list(@ShellOption(value = {"--tenant", "-T"}) String tenant) {
        List<UserDocument> all = userService.all(tenant);
        if (all.isEmpty()) {
            return "(no users in tenant '" + tenant + "')";
        }
        return Tables.render(
                List.of("NAME", "TITLE", "EMAIL", "STATUS"),
                List.<Function<UserDocument, @Nullable Object>>of(
                        UserDocument::getName,
                        UserDocument::getTitle,
                        UserDocument::getEmail,
                        UserDocument::getStatus),
                all);
    }

    @ShellMethod(key = "user show", value = "Show a user.")
    public String show(
            @ShellOption(value = {"--tenant", "-T"}) String tenant,
            @ShellOption(value = {"--name", "-n"}) String name) {
        return userService.findByTenantAndName(tenant, name)
                .map(UserCommands::renderOne)
                .orElse("User '" + name + "' not found in tenant '" + tenant + "'.");
    }

    @ShellMethod(key = "user create", value = "Create a user. Password is prompted (masked) when --password is omitted.")
    public String create(
            @ShellOption(value = {"--tenant", "-T"}) String tenant,
            @ShellOption(value = {"--name", "-n"}) String name,
            @ShellOption(value = {"--title", "-t"}, defaultValue = ShellOption.NULL) @Nullable String title,
            @ShellOption(value = {"--email", "-e"}, defaultValue = ShellOption.NULL) @Nullable String email,
            @ShellOption(value = {"--password", "-p"}, defaultValue = ShellOption.NULL,
                    help = "Plaintext. Stored as BCrypt hash. Omit to be prompted with masked input.")
            @Nullable String password,
            @ShellOption(value = {"--no-password"}, defaultValue = "false",
                    help = "Create the user without setting a password (e.g. for SSO-only accounts).")
            boolean noPassword) {
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
        UserDocument user = userService.create(tenant, name, hash, title, email);
        return "Created:\n" + renderOne(user);
    }

    @ShellMethod(key = "user update", value = "Update mutable fields of a user.")
    public String update(
            @ShellOption(value = {"--tenant", "-T"}) String tenant,
            @ShellOption(value = {"--name", "-n"}) String name,
            @ShellOption(value = {"--title", "-t"}, defaultValue = ShellOption.NULL) @Nullable String title,
            @ShellOption(value = {"--email", "-e"}, defaultValue = ShellOption.NULL) @Nullable String email,
            @ShellOption(value = {"--status", "-s"}, defaultValue = ShellOption.NULL,
                    help = "ACTIVE | DISABLED")
            @Nullable UserStatus status) {
        UserDocument user = userService.update(tenant, name, title, email, status);
        return "Updated:\n" + renderOne(user);
    }

    @ShellMethod(key = "user set-password",
            value = "Reset a user's password. Plaintext prompted (masked) when --password is omitted.")
    public String setPassword(
            @ShellOption(value = {"--tenant", "-T"}) String tenant,
            @ShellOption(value = {"--name", "-n"}) String name,
            @ShellOption(value = {"--password", "-p"}, defaultValue = ShellOption.NULL) @Nullable String password) {
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

    @ShellMethod(key = "user delete", value = "Hard-delete a user.")
    public String delete(
            @ShellOption(value = {"--tenant", "-T"}) String tenant,
            @ShellOption(value = {"--name", "-n"}) String name) {
        userService.delete(tenant, name);
        return "Deleted user '" + name + "' in tenant '" + tenant + "'.";
    }

    private static String renderOne(UserDocument u) {
        return "  tenantId  : " + u.getTenantId() + "\n"
                + "  name      : " + u.getName() + "\n"
                + "  title     : " + (u.getTitle() == null ? "" : u.getTitle()) + "\n"
                + "  email     : " + (u.getEmail() == null ? "" : u.getEmail()) + "\n"
                + "  status    : " + u.getStatus() + "\n"
                + "  hasHash   : " + (u.getPasswordHash() != null) + "\n"
                + "  created   : " + (u.getCreatedAt() == null ? "" : u.getCreatedAt()) + "\n"
                + "  id        : " + (u.getId() == null ? "" : u.getId());
    }
}
