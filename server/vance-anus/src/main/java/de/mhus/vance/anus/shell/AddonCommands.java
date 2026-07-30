package de.mhus.vance.anus.shell;

import de.mhus.vance.anus.access.RequiresAuth;
import de.mhus.vance.shared.addon.AddonDocument;
import de.mhus.vance.shared.addon.AddonService;
import de.mhus.vance.shared.addon.VanceAddon;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

/**
 * CRUD over {@link AddonDocument}. The admin can register new addons
 * (pointing at a bundled marker or an external {@code .vab} URL),
 * flip the {@code enabled} flag, change the source path and delete
 * rows. Listing shows every row including disabled — disabled addons
 * are only filtered out from {@code GET /face/addons} in the brain.
 *
 * <p>Spec: {@code specification/addon-system.md}.
 */
@Component
@RequiresAuth
@RequiredArgsConstructor
public class AddonCommands {

    private final AddonService addonService;
    /**
     * Every {@link VanceAddon} bean registered in this anus process — i.e. the
     * addons whose anus/lib JAR is actually on the classpath right now. Spring
     * injects an empty list when none are loaded. This is the "live" truth,
     * distinct from the db.addons "configured" truth that {@link #list()} shows.
     */
    private final List<VanceAddon> loadedAddons;

    @Command(name = {"addon", "list"}, description = "List all addons (including disabled).")
    public String list() {
        List<AddonDocument> all = addonService.listAll();
        if (all.isEmpty()) {
            return "(no addons)";
        }
        return Tables.render(
                List.of("NAME", "PATH", "ENABLED", "CHECKSUM", "CREATED"),
                List.<Function<AddonDocument, @Nullable Object>>of(
                        AddonDocument::getName,
                        AddonDocument::getPath,
                        AddonDocument::isEnabled,
                        a -> a.getChecksum() != null ? "set" : "-",
                        AddonDocument::getCreatedAt),
                all);
    }

    @Command(name = {"addon", "active"},
            description = "List addons actually loaded in THIS anus process (live beans), "
                    + "cross-checked against db.addons. Unlike 'addon list' (db "
                    + "configuration) this reflects the real classpath.")
    public String active() {
        if (loadedAddons.isEmpty()) {
            return "(no addons loaded in this process)";
        }
        Map<String, AddonDocument> byName = addonService.listAll().stream()
                .collect(Collectors.toMap(AddonDocument::getName, d -> d, (a, b) -> a));
        List<VanceAddon> sorted = loadedAddons.stream()
                .sorted(Comparator.comparing(VanceAddon::id))
                .toList();
        return Tables.render(
                List.of("ID", "NAME", "STATUS", "DB"),
                List.<Function<VanceAddon, @Nullable Object>>of(
                        VanceAddon::id,
                        VanceAddon::displayName,
                        a -> a.status() != null ? a.status() : "",
                        a -> {
                            AddonDocument doc = byName.get(a.id());
                            return doc == null ? "— (not in db)"
                                    : (doc.isEnabled() ? "enabled" : "disabled");
                        }),
                sorted);
    }

    @Command(name = {"addon", "show"}, description = "Show one addon by name.")
    public String show(@Option(longName = "name", shortName = 'n', required = true) String name) {
        return addonService.findByName(name)
                .map(AddonCommands::renderOne)
                .orElse("Addon '" + name + "' not found.");
    }

    @Command(name = {"addon", "create"},
            description = "Create a new addon row. Fails if the name already exists — "
                    + "use 'addon update' to change the path of an existing row. "
                    + "Optional --checksum (format 'sha256:<hex>') is verified on download.")
    public String create(
            @Option(longName = "name", shortName = 'n', required = true) String name,
            @Option(longName = "path", shortName = 'p', required = true) String path,
            @Option(longName = "checksum", shortName = 'c') @Nullable String checksum) {
        AddonDocument addon = addonService.create(name, path, checksum);
        return "Created addon:\n" + renderOne(addon);
    }

    @Command(name = {"addon", "update"}, description = "Change the source path of an existing addon.")
    public String update(
            @Option(longName = "name", shortName = 'n', required = true) String name,
            @Option(longName = "path", shortName = 'p', required = true) String path) {
        AddonDocument addon = addonService.updatePath(name, path);
        return "Updated addon:\n" + renderOne(addon);
    }

    @Command(name = {"addon", "set-checksum"},
            description = "Set or clear the expected SHA-256 of the source .vab. "
                    + "Format: 'sha256:<hex>'. Pass an empty string to clear.")
    public String setChecksum(
            @Option(longName = "name", shortName = 'n', required = true) String name,
            @Option(longName = "checksum", shortName = 'c', required = true) String checksum) {
        String value = checksum.isBlank() ? null : checksum;
        AddonDocument addon = addonService.updateChecksum(name, value);
        return "Updated addon:\n" + renderOne(addon);
    }

    @Command(name = {"addon", "enable"},
            description = "Mark an addon enabled — it reappears in GET /face/addons "
                    + "and is unpacked on the next container restart.")
    public String enable(@Option(longName = "name", shortName = 'n', required = true) String name) {
        AddonDocument addon = addonService.setEnabled(name, true);
        return "Enabled addon:\n" + renderOne(addon);
    }

    @Command(name = {"addon", "disable"},
            description = "Mark an addon disabled — hidden from GET /face/addons "
                    + "and skipped by the bootstrap on the next container restart. "
                    + "Works on bundled addons too.")
    public String disable(@Option(longName = "name", shortName = 'n', required = true) String name) {
        AddonDocument addon = addonService.setEnabled(name, false);
        return "Disabled addon:\n" + renderOne(addon);
    }

    @Command(name = {"addon", "delete"},
            description = "Hard-delete an addon row. The /shared/addons/<name>/ cache "
                    + "on disk is left intact and must be cleaned up separately.")
    public String delete(@Option(longName = "name", shortName = 'n', required = true) String name) {
        addonService.delete(name);
        return "Deleted addon '" + name + "'.";
    }

    // ── Rendering ────────────────────────────────────────────────────

    private static String renderOne(AddonDocument doc) {
        return "  name:      " + doc.getName() + "\n"
                + "  path:      " + doc.getPath() + "\n"
                + "  enabled:   " + doc.isEnabled() + "\n"
                + "  checksum:  " + (doc.getChecksum() != null ? doc.getChecksum() : "(none)") + "\n"
                + "  createdAt: " + doc.getCreatedAt();
    }
}
