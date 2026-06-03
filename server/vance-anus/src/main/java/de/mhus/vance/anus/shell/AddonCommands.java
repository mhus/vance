package de.mhus.vance.anus.shell;

import de.mhus.vance.anus.access.RequiresAuth;
import de.mhus.vance.shared.addon.AddonDocument;
import de.mhus.vance.shared.addon.AddonService;
import java.util.List;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

/**
 * CRUD over {@link AddonDocument}. The admin can register new addons
 * (pointing at a bundled marker or an external {@code .vab} URL),
 * flip the {@code enabled} flag, change the source path and delete
 * rows. Listing shows every row including disabled — disabled addons
 * are only filtered out from {@code GET /face/addons} in the brain.
 *
 * <p>Spec: {@code specification/addon-system.md}.
 */
@ShellComponent
@RequiresAuth
@RequiredArgsConstructor
public class AddonCommands {

    private final AddonService addonService;

    @ShellMethod(key = "addon list", value = "List all addons (including disabled).")
    public String list() {
        List<AddonDocument> all = addonService.listAll();
        if (all.isEmpty()) {
            return "(no addons)";
        }
        return Tables.render(
                List.of("NAME", "PATH", "ENABLED", "CREATED"),
                List.<Function<AddonDocument, @Nullable Object>>of(
                        AddonDocument::getName,
                        AddonDocument::getPath,
                        AddonDocument::isEnabled,
                        AddonDocument::getCreatedAt),
                all);
    }

    @ShellMethod(key = "addon show", value = "Show one addon by name.")
    public String show(@ShellOption(value = {"--name", "-n"}) String name) {
        return addonService.findByName(name)
                .map(AddonCommands::renderOne)
                .orElse("Addon '" + name + "' not found.");
    }

    @ShellMethod(key = "addon create",
            value = "Create a new addon row. Fails if the name already exists — "
                    + "use 'addon update' to change the path of an existing row.")
    public String create(
            @ShellOption(value = {"--name", "-n"}) String name,
            @ShellOption(value = {"--path", "-p"}) String path) {
        AddonDocument addon = addonService.create(name, path);
        return "Created addon:\n" + renderOne(addon);
    }

    @ShellMethod(key = "addon update", value = "Change the source path of an existing addon.")
    public String update(
            @ShellOption(value = {"--name", "-n"}) String name,
            @ShellOption(value = {"--path", "-p"}) String path) {
        AddonDocument addon = addonService.updatePath(name, path);
        return "Updated addon:\n" + renderOne(addon);
    }

    @ShellMethod(key = "addon enable",
            value = "Mark an addon enabled — it reappears in GET /face/addons "
                    + "and is unpacked on the next container restart.")
    public String enable(@ShellOption(value = {"--name", "-n"}) String name) {
        AddonDocument addon = addonService.setEnabled(name, true);
        return "Enabled addon:\n" + renderOne(addon);
    }

    @ShellMethod(key = "addon disable",
            value = "Mark an addon disabled — hidden from GET /face/addons "
                    + "and skipped by the bootstrap on the next container restart. "
                    + "Works on bundled addons too.")
    public String disable(@ShellOption(value = {"--name", "-n"}) String name) {
        AddonDocument addon = addonService.setEnabled(name, false);
        return "Disabled addon:\n" + renderOne(addon);
    }

    @ShellMethod(key = "addon delete",
            value = "Hard-delete an addon row. The /shared/addons/<name>/ cache "
                    + "on disk is left intact and must be cleaned up separately.")
    public String delete(@ShellOption(value = {"--name", "-n"}) String name) {
        addonService.delete(name);
        return "Deleted addon '" + name + "'.";
    }

    // ── Rendering ────────────────────────────────────────────────────

    private static String renderOne(AddonDocument doc) {
        return "  name:      " + doc.getName() + "\n"
                + "  path:      " + doc.getPath() + "\n"
                + "  enabled:   " + doc.isEnabled() + "\n"
                + "  createdAt: " + doc.getCreatedAt();
    }
}
