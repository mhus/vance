package de.mhus.vance.anus.shell;

import de.mhus.vance.anus.access.RequiresAuth;
import de.mhus.vance.anus.maintenance.TenantMaintenanceService;
import de.mhus.vance.shared.tenant.TenantDocument;
import de.mhus.vance.shared.tenant.TenantService;
import java.util.List;
import java.util.function.Function;
import org.apache.commons.lang3.StringUtils;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

/**
 * CRUD over {@link TenantDocument}. {@code tenant create} delegates to
 * {@link TenantService#ensure(String, String)} which is idempotent and also
 * mints the JWT signing key on first call.
 *
 * <p>{@code tenant delete} does not remove just the row — that would orphan the tenant's projects, accounts and
 * every collection keyed by it. It runs {@link TenantMaintenanceService}, which clears all of that first, and
 * needs the tenant switched off ({@code tenant update --enabled false}) and its name typed. {@code tenant
 * inspect} is the dry run.
 */
@Component
@RequiresAuth
public class TenantCommands {

    private final TenantService tenantService;
    private final TenantMaintenanceService maintenanceService;
    // Lazy LineReader to avoid the Spring-Shell bean cycle — see AccessCommands.
    private final ObjectProvider<LineReader> lineReader;

    public TenantCommands(
            TenantService tenantService,
            TenantMaintenanceService maintenanceService,
            ObjectProvider<LineReader> lineReader) {
        this.tenantService = tenantService;
        this.maintenanceService = maintenanceService;
        this.lineReader = lineReader;
    }

    @Command(
            name = {"tenant", "list"},
            description = "List all tenants.")
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

    @Command(
            name = {"tenant", "show"},
            description = "Show a tenant by name.")
    public String show(@Option(longName = "name", shortName = 'n', required = true) String name) {
        return tenantService.findByName(name).map(TenantCommands::renderOne).orElse("Tenant '" + name + "' not found.");
    }

    @Command(
            name = {"tenant", "create"},
            description = "Create (or ensure) a tenant. Idempotent — also mints the JWT signing key.")
    public String create(
            @Option(longName = "name", shortName = 'n', required = true) String name,
            @Option(longName = "title", shortName = 't') @Nullable String title) {
        TenantDocument tenant = tenantService.ensure(name, title);
        return "Ensured tenant:\n" + renderOne(tenant);
    }

    @Command(
            name = {"tenant", "update"},
            description = "Update a tenant's title and/or enabled flag.")
    public String update(
            @Option(longName = "name", shortName = 'n', required = true) String name,
            @Option(longName = "title", shortName = 't') @Nullable String title,
            @Option(longName = "enabled") @Nullable Boolean enabled) {
        TenantDocument tenant = tenantService.update(name, title, enabled);
        return "Updated:\n" + renderOne(tenant);
    }

    @Command(
            name = {"tenant", "inspect"},
            description = "Count what a tenant owns: projects, accounts and the rows in every tenant-keyed collection."
                    + " Writes nothing — the dry run for 'tenant delete'.")
    public String inspect(@Option(longName = "name", shortName = 'n', required = true) String name) {
        try {
            return MaintenanceOutput.render(maintenanceService.inspect(name), "tenant");
        } catch (TenantService.TenantNotFoundException e) {
            return e.getMessage();
        }
    }

    @Command(
            name = {"tenant", "delete"},
            description = "Hard-delete a tenant and everything it owns: its projects, accounts and every row keyed by"
                    + " it. Irreversible. The tenant must be disabled first (tenant update --enabled false).")
    public String delete(
            @Option(longName = "name", shortName = 'n', required = true) String name,
            @Option(
                            longName = "confirm",
                            description = "Type the tenant name here to confirm. Required when there is no terminal"
                                    + " to ask on (--sudo).")
                    @Nullable
                    String confirm,
            @Option(
                            longName = "force",
                            description = "Proceed past projects that could not be drained off their pod and past"
                                    + " accounts that block. Only when the holder is known to be gone.",
                            defaultValue = "false")
                    boolean force) {
        String problem = confirmed(name, confirm);
        if (problem != null) {
            return problem;
        }
        try {
            return MaintenanceOutput.render(maintenanceService.delete(name, force), "tenant");
        } catch (TenantService.TenantNotFoundException
                | TenantService.TenantProtectedException
                | TenantService.TenantStillEnabledException e) {
            return "Not done — " + e.getMessage();
        } catch (RuntimeException e) {
            return "Delete FAILED — " + e.getMessage();
        }
    }

    private @Nullable String confirmed(String name, @Nullable String confirm) {
        String answer = confirm;
        if (StringUtils.isBlank(answer)) {
            LineReader reader = lineReader.getIfAvailable();
            if (reader == null) {
                return refuseWithoutConfirmation(name);
            }
            try {
                answer = reader.readLine("Type the tenant name '" + name + "' to confirm the delete: ");
            } catch (EndOfFileException | UserInterruptException e) {
                // --sudo has a LineReader bean but no stdin behind it; Ctrl-C at the prompt is a person
                // declining. Both end in the one sentence that says what to pass.
                return refuseWithoutConfirmation(name);
            }
        }
        if (!name.equals(answer == null ? null : answer.trim())) {
            return "Confirmation did not match '" + name + "' — nothing was done.";
        }
        return null;
    }

    private static String refuseWithoutConfirmation(String name) {
        return "Refusing to delete without confirmation — pass --confirm " + name;
    }

    private static String renderOne(TenantDocument t) {
        return "  name      : " + t.getName() + "\n"
                + "  title     : " + (t.getTitle() == null ? "" : t.getTitle()) + "\n"
                + "  enabled   : " + t.isEnabled() + "\n"
                + "  created   : " + (t.getCreatedAt() == null ? "" : t.getCreatedAt()) + "\n"
                + "  id        : " + (t.getId() == null ? "" : t.getId());
    }
}
