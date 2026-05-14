package de.mhus.vance.foot.command;

import de.mhus.vance.api.kit.ProjectKitEntry;
import de.mhus.vance.api.kit.ProjectKitsCatalogDto;
import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.connection.BrainRestClientService;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * {@code /kit-list} — print the tenant's project-kits catalog so the
 * operator can pick a name for {@code /project-create --kit <name>}.
 */
@Component
public class KitListCommand implements SlashCommand {

    private final BrainRestClientService rest;
    private final ChatTerminal terminal;
    private final FootConfig config;

    public KitListCommand(
            BrainRestClientService rest,
            ChatTerminal terminal,
            FootConfig config) {
        this.rest = rest;
        this.terminal = terminal;
        this.config = config;
    }

    @Override
    public String name() {
        return "kit-list";
    }

    @Override
    public String description() {
        return "List project-kits in this tenant. Use the name with /project-create --kit.";
    }

    @Override
    public void execute(List<String> args) throws Exception {
        if (!args.isEmpty()) {
            terminal.error("Usage: /kit-list");
            return;
        }
        String path = "/brain/" + config.getAuth().getTenant() + "/admin/project-kits/catalog";
        ProjectKitsCatalogDto catalog = rest.get(path, ProjectKitsCatalogDto.class);

        if (catalog.getKits() == null || catalog.getKits().isEmpty()) {
            terminal.info("Catalog is empty.");
            return;
        }
        terminal.info(String.format("%-28s %-32s %s", "NAME", "TITLE", "DESCRIPTION"));
        for (ProjectKitEntry e : catalog.getKits()) {
            terminal.info(String.format("%-28s %-32s %s",
                    truncate(e.getName(), 28),
                    truncate(Objects.toString(e.getTitle(), ""), 32),
                    truncate(Objects.toString(e.getDescription(), ""), 60)));
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
    }
}
