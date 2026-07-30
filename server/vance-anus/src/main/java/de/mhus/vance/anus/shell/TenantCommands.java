package de.mhus.vance.anus.shell;

import de.mhus.vance.anus.access.RequiresAuth;
import de.mhus.vance.shared.tenant.TenantDocument;
import de.mhus.vance.shared.tenant.TenantService;
import java.util.List;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

/**
 * CRUD over {@link TenantDocument}. {@code tenant create} delegates to
 * {@link TenantService#ensure(String, String)} which is idempotent and also
 * mints the JWT signing key on first call. There is intentionally no
 * {@code tenant delete} — {@link TenantService} does not support removal,
 * and a hard-deleted tenant would orphan all its projects/users/teams.
 */
@Component
@RequiresAuth
@RequiredArgsConstructor
public class TenantCommands {

    private final TenantService tenantService;

    @Command(name = {"tenant", "list"}, description = "List all tenants.")
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

    @Command(name = {"tenant", "show"}, description = "Show a tenant by name.")
    public String show(@Option(longName = "name", shortName = 'n', required = true) String name) {
        return tenantService.findByName(name)
                .map(TenantCommands::renderOne)
                .orElse("Tenant '" + name + "' not found.");
    }

    @Command(name = {"tenant", "create"},
            description = "Create (or ensure) a tenant. Idempotent — also mints the JWT signing key.")
    public String create(
            @Option(longName = "name", shortName = 'n', required = true) String name,
            @Option(longName = "title", shortName = 't') @Nullable String title) {
        TenantDocument tenant = tenantService.ensure(name, title);
        return "Ensured tenant:\n" + renderOne(tenant);
    }

    @Command(name = {"tenant", "update"}, description = "Update a tenant's title and/or enabled flag.")
    public String update(
            @Option(longName = "name", shortName = 'n', required = true) String name,
            @Option(longName = "title", shortName = 't') @Nullable String title,
            @Option(longName = "enabled") @Nullable Boolean enabled) {
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
