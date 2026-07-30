package de.mhus.vance.anus.shell;

import de.mhus.vance.anus.access.RequiresAuth;
import de.mhus.vance.anus.brain.AnusBrainClient;
import de.mhus.vance.anus.brain.AnusBrainClient.Response;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

/**
 * CRUD over {@link ProjectDocument}. Hard delete is intentionally unsupported
 * — the lifecycle ends at {@link de.mhus.vance.shared.project.ProjectStatus#CLOSED}
 * via {@code project close}; that's how Brain expects it.
 */
@Component
@RequiresAuth
@RequiredArgsConstructor
public class ProjectCommands {

    private final ProjectService projectService;
    private final AnusBrainClient brainClient;

    @Command(name = {"project", "list"}, description = "List projects in a tenant.")
    public String list(@Option(longName = "tenant", shortName = 'T', required = true) String tenant) {
        List<ProjectDocument> all = projectService.all(tenant);
        if (all.isEmpty()) {
            return "(no projects in tenant '" + tenant + "')";
        }
        return Tables.render(
                List.of("NAME", "TITLE", "STATUS", "KIND", "ENABLED", "GROUP", "HOMENODE"),
                List.<Function<ProjectDocument, @Nullable Object>>of(
                        ProjectDocument::getName,
                        ProjectDocument::getTitle,
                        ProjectDocument::getStatus,
                        ProjectDocument::getKind,
                        ProjectDocument::isEnabled,
                        ProjectDocument::getProjectGroupId,
                        ProjectDocument::getHomeNode),
                all);
    }

    @Command(name = {"project", "show"}, description = "Show a project.")
    public String show(
            @Option(longName = "tenant", shortName = 'T', required = true) String tenant,
            @Option(longName = "name", shortName = 'n', required = true) String name) {
        return projectService.findByTenantAndName(tenant, name)
                .map(ProjectCommands::renderOne)
                .orElse("Project '" + name + "' not found in tenant '" + tenant + "'.");
    }

    @Command(name = {"project", "create"}, description = "Create a NORMAL project.")
    public String create(
            @Option(longName = "tenant", shortName = 'T', required = true) String tenant,
            @Option(longName = "name", shortName = 'n', required = true) String name,
            @Option(longName = "title", shortName = 't') @Nullable String title,
            @Option(longName = "group", shortName = 'g') @Nullable String group,
            @Option(longName = "teams",
                    description = "Comma-separated team names")
            @Nullable String teams) {
        ProjectDocument project = projectService.create(tenant, name, title, group, parseList(teams));
        return "Created:\n" + renderOne(project);
    }

    @Command(name = {"project", "update"}, description = "Update mutable fields of a project.")
    public String update(
            @Option(longName = "tenant", shortName = 'T', required = true) String tenant,
            @Option(longName = "name", shortName = 'n', required = true) String name,
            @Option(longName = "title", shortName = 't') @Nullable String title,
            @Option(longName = "enabled") @Nullable Boolean enabled,
            @Option(longName = "group", shortName = 'g') @Nullable String group,
            @Option(longName = "clear-group",
                    description = "Detach the project from any project group.",
                    defaultValue = "false")
            boolean clearGroup,
            @Option(longName = "teams",
                    description = "Comma-separated team names — replaces the list wholesale")
            @Nullable String teams) {
        ProjectDocument project = projectService.update(
                tenant, name, title, enabled, group, clearGroup, parseList(teams));
        return "Updated:\n" + renderOne(project);
    }

    @Command(name = {"project", "close"}, description = "Close a project (soft-delete, lifecycle terminal).")
    public String close(
            @Option(longName = "tenant", shortName = 'T', required = true) String tenant,
            @Option(longName = "name", shortName = 'n', required = true) String name,
            @Option(longName = "closed-group",
                    description = "Project-group bucket the closed project moves into.",
                    defaultValue = "_closed")
            String closedGroup) {
        // Direct DB close — workspace disposition / engine teardown happens
        // out of band. For a fully-orchestrated close use the Brain's
        // 'DELETE /brain/{tenant}/admin/projects/{name}' (see project resume
        // for the pattern), but that requires the project's home pod to be
        // reachable. This local path is the operator's last resort.
        ProjectDocument project = projectService.close(tenant, name, closedGroup);
        return "Closed:\n" + renderOne(project);
    }

    // ─── Brain-orchestrated lifecycle ──────────────────────────────────────
    //
    // These commands go through Brain REST instead of the local repository
    // because the lifecycle owns more than the document — workspace folders,
    // engine processes, pod claims. Touching the document directly here
    // would diverge from the Brain's view and is unsupported.

    @Command(name = {"project", "suspend"},
            description = "Stop engines + snapshot workspace + mark SUSPENDED. Brain-orchestrated.")
    public String suspend(
            @Option(longName = "tenant", shortName = 'T', required = true) String tenant,
            @Option(longName = "name", shortName = 'n', required = true) String name) {
        Response response = brainClient.post(
                tenant, "/brain/" + tenant + "/admin/projects/" + name + "/suspend", "{}");
        return formatResponse("Suspend", tenant, name, response);
    }

    @Command(name = {"project", "resume"},
            description = "Claim project for a pod, recover workspace, start engines, mark RUNNING.")
    public String resume(
            @Option(longName = "tenant", shortName = 'T', required = true) String tenant,
            @Option(longName = "name", shortName = 'n', required = true) String name) {
        Response response = brainClient.post(
                tenant, "/brain/" + tenant + "/admin/projects/" + name + "/resume", "{}");
        return formatResponse("Resume", tenant, name, response);
    }

    private static String formatResponse(String op, String tenant, String name, Response response) {
        if (response.isSuccess()) {
            return op + " OK — tenant='" + tenant + "' project='" + name + "'\n"
                    + response.body();
        }
        return op + " FAILED — HTTP " + response.statusCode() + "\n" + response.body();
    }

    private static @Nullable List<String> parseList(@Nullable String csv) {
        if (StringUtils.isBlank(csv)) {
            return null;
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static String renderOne(ProjectDocument p) {
        return "  tenantId  : " + p.getTenantId() + "\n"
                + "  name      : " + p.getName() + "\n"
                + "  title     : " + (p.getTitle() == null ? "" : p.getTitle()) + "\n"
                + "  status    : " + p.getStatus() + "\n"
                + "  kind      : " + p.getKind() + "\n"
                + "  enabled   : " + p.isEnabled() + "\n"
                + "  group     : " + (p.getProjectGroupId() == null ? "" : p.getProjectGroupId()) + "\n"
                + "  teams     : " + p.getTeamIds() + "\n"
                + "  homeNode: " + (p.getHomeNode() == null ? "" : p.getHomeNode()) + "\n"
                + "  claimedAt : " + (p.getClaimedAt() == null ? "" : p.getClaimedAt()) + "\n"
                + "  created   : " + (p.getCreatedAt() == null ? "" : p.getCreatedAt()) + "\n"
                + "  id        : " + (p.getId() == null ? "" : p.getId());
    }
}
