package de.mhus.vance.foot.command;

import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.ProjectGroupListResponse;
import de.mhus.vance.api.ws.ProjectGroupSummary;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * {@code /projectgroup-list} — lists project groups in the current tenant.
 * No request payload.
 */
@Component
public class ProjectGroupListCommand implements SlashCommand {

    private final ConnectionService connection;
    private final ChatTerminal terminal;
    private final SuggestionCache suggestionCache;

    public ProjectGroupListCommand(
            ConnectionService connection,
            ChatTerminal terminal,
            SuggestionCache suggestionCache) {
        this.connection = connection;
        this.terminal = terminal;
        this.suggestionCache = suggestionCache;
    }

    @Override
    public String name() {
        return "projectgroup-list";
    }

    @Override
    public String description() {
        return "List project groups in the current tenant.";
    }

    @Override
    public void execute(List<String> args) throws Exception {
        if (!args.isEmpty()) {
            terminal.error("Usage: /projectgroup-list");
            return;
        }
        ProjectGroupListResponse response = connection.request(
                MessageType.PROJECTGROUP_LIST,
                null,
                ProjectGroupListResponse.class,
                Duration.ofSeconds(10));

        if (response.getGroups() != null) {
            suggestionCache.rememberProjectGroups(response.getGroups().stream()
                    .map(ProjectGroupSummary::getName)
                    .filter(s -> s != null && !s.isBlank())
                    .toList());
        }
        if (response.getGroups() == null || response.getGroups().isEmpty()) {
            terminal.info("No project groups.");
            return;
        }
        terminal.info(String.format("%-24s %-10s %s", "NAME", "ENABLED", "TITLE"));
        for (ProjectGroupSummary g : response.getGroups()) {
            terminal.info(String.format("%-24s %-10s %s",
                    truncate(g.getName(), 24),
                    g.isEnabled() ? "yes" : "no",
                    Objects.toString(g.getTitle(), "")));
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
    }
}
