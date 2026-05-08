package de.mhus.vance.anus.shell;

import de.mhus.vance.anus.access.RequiresAuth;
import de.mhus.vance.shared.tenant.TenantDocument;
import de.mhus.vance.shared.tenant.TenantService;
import java.util.List;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

/**
 * CRUD over {@link TenantDocument}. {@code tenant create} delegates to
 * {@link TenantService#ensure(String, String)} which is idempotent and also
 * mints the JWT signing key on first call. There is intentionally no
 * {@code tenant delete} — {@link TenantService} does not support removal,
 * and a hard-deleted tenant would orphan all its projects/users/teams.
 */
@ShellComponent
@RequiresAuth
@RequiredArgsConstructor
public class TenantCommands {

    private final TenantService tenantService;

    @ShellMethod(key = "tenant list", value = "List all tenants.")
    public String list() {
        List<TenantDocument> all = tenantService.all();
        if (all.isEmpty()) {
            return "(no tenants)";
        }
        return Tables.render(
                List.of("NAME", "TITLE", "ENABLED", "CREATED"),
                List.<Function<TenantDocument, @Nullable Object>>of(
                        TenantDocument::getName,
                        TenantDocument::getTitle,
                        TenantDocument::isEnabled,
                        TenantDocument::getCreatedAt),
                all);
    }

    @ShellMethod(key = "tenant show", value = "Show a tenant by name.")
    public String show(@ShellOption(value = {"--name", "-n"}) String name) {
        return tenantService.findByName(name)
                .map(TenantCommands::renderOne)
                .orElse("Tenant '" + name + "' not found.");
    }

    @ShellMethod(key = "tenant create",
            value = "Create (or ensure) a tenant. Idempotent — also mints the JWT signing key.")
    public String create(
            @ShellOption(value = {"--name", "-n"}) String name,
            @ShellOption(value = {"--title", "-t"}, defaultValue = ShellOption.NULL) @Nullable String title) {
        TenantDocument tenant = tenantService.ensure(name, title);
        return "Ensured tenant:\n" + renderOne(tenant);
    }

    @ShellMethod(key = "tenant update", value = "Update a tenant's title and/or enabled flag.")
    public String update(
            @ShellOption(value = {"--name", "-n"}) String name,
            @ShellOption(value = {"--title", "-t"}, defaultValue = ShellOption.NULL) @Nullable String title,
            @ShellOption(value = {"--enabled"}, defaultValue = ShellOption.NULL) @Nullable Boolean enabled) {
        TenantDocument tenant = tenantService.update(name, title, enabled);
        return "Updated:\n" + renderOne(tenant);
    }

    private static String renderOne(TenantDocument t) {
        return "  name      : " + t.getName() + "\n"
                + "  title     : " + (t.getTitle() == null ? "" : t.getTitle()) + "\n"
                + "  enabled   : " + t.isEnabled() + "\n"
                + "  created   : " + (t.getCreatedAt() == null ? "" : t.getCreatedAt()) + "\n"
                + "  id        : " + (t.getId() == null ? "" : t.getId());
    }
}
