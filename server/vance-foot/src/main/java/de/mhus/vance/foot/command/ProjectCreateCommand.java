package de.mhus.vance.foot.command;

import de.mhus.vance.api.projects.ProjectCreateRequest;
import de.mhus.vance.api.projects.ProjectDto;
import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.connection.BrainRestClientService;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.net.http.HttpResponse;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code /project-create <name> [title] [--group <projectGroupId>] [--kit <kitName>]}
 * — create a NORMAL project in the current tenant. Hits
 * {@code POST /brain/{tenant}/admin/projects}; the catalog-listed kit
 * named by {@code --kit} (if any) is installed in the same request.
 *
 * <p>The kit name must match an entry in the tenant project-kits
 * catalog (see {@code project-kits-catalog.md}). Use {@code /kit-list}
 * to see what's available.
 */
@Component
public class ProjectCreateCommand implements SlashCommand {

    private final BrainRestClientService rest;
    private final ChatTerminal terminal;
    private final FootConfig config;
    private final ObjectMapper json;

    public ProjectCreateCommand(
            BrainRestClientService rest,
            ChatTerminal terminal,
            FootConfig config,
            ObjectMapper json) {
        this.rest = rest;
        this.terminal = terminal;
        this.config = config;
        this.json = json;
    }

    @Override
    public String name() {
        return "project-create";
    }

    @Override
    public String description() {
        return "Create a project in the current tenant. "
                + "Usage: /project-create <name> [title] [--group <groupId>] [--kit <kitName>]";
    }

    @Override
    public void execute(List<String> args) throws Exception {
        Parsed parsed = parse(args);
        if (parsed == null) {
            return;
        }
        ProjectCreateRequest request = ProjectCreateRequest.builder()
                .name(parsed.name)
                .title(parsed.title)
                .projectGroupId(parsed.group)
                .kitName(parsed.kit)
                .build();
        String path = "/brain/" + config.getAuth().getTenant() + "/admin/projects";
        HttpResponse<String> response = rest.postRaw(path, request);

        ProjectDto created = json.readValue(response.body(), ProjectDto.class);
        terminal.info("Created project '" + created.getName() + "'"
                + (created.getTitle() == null ? "" : " — " + created.getTitle())
                + (created.getProjectGroupId() == null ? "" : " (group: " + created.getProjectGroupId() + ")")
                + " status=" + created.getStatus());

        String kitWarning = response.headers().firstValue("X-Vance-Kit-Install-Error").orElse(null);
        if (parsed.kit != null) {
            if (kitWarning == null) {
                terminal.info("Kit '" + parsed.kit + "' installed.");
            } else {
                terminal.error("Project created, but kit '" + parsed.kit
                        + "' install failed: " + kitWarning);
            }
        }
    }

    private @Nullable Parsed parse(List<String> args) {
        if (args.isEmpty()) {
            terminal.error("Usage: /project-create <name> [title] [--group <groupId>] [--kit <kitName>]");
            return null;
        }
        Parsed result = new Parsed();
        result.name = args.get(0);
        // Positional title (anything before the first flag); flags
        // collect their next token. Keeps the surface friendly without
        // pulling in picocli for one command.
        for (int i = 1; i < args.size(); i++) {
            String arg = args.get(i);
            if ("--kit".equals(arg) || "-k".equals(arg)) {
                if (i + 1 >= args.size()) {
                    terminal.error("--kit needs a value");
                    return null;
                }
                result.kit = args.get(++i);
            } else if ("--group".equals(arg) || "-g".equals(arg)) {
                if (i + 1 >= args.size()) {
                    terminal.error("--group needs a value");
                    return null;
                }
                result.group = args.get(++i);
            } else if (!arg.startsWith("--") && result.title == null) {
                result.title = arg;
            } else {
                terminal.error("Unknown argument: " + arg);
                return null;
            }
        }
        return result;
    }

    private static final class Parsed {
        String name;
        @Nullable String title;
        @Nullable String group;
        @Nullable String kit;
    }
}
