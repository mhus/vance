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
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

/**
 * CRUD over {@link ProjectDocument}. Hard delete is intentionally unsupported
 * — the lifecycle ends at {@link de.mhus.vance.shared.project.ProjectStatus#CLOSED}
 * via {@code project close}; that's how Brain expects it.
 */
@ShellComponent
@RequiresAuth
@RequiredArgsConstructor
public class ProjectCommands {

    private final ProjectService projectService;
    private final AnusBrainClient brainClient;

    @ShellMethod(key = "project list", value = "List projects in a tenant.")
    public String list(@ShellOption(value = {"--tenant", "-T"}) String tenant) {
        List<ProjectDocument> all = projectService.all(tenant);
        if (all.isEmpty()) {
            return "(no projects in tenant '" + tenant + "')";
        }
        return Tables.render(
                List.of("NAME", "TITLE", "STATUS", "KIND", "ENABLED", "GROUP", "HOMECLUSTER"),
                List.<Function<ProjectDocument, @Nullable Object>>of(
                        ProjectDocument::getName,
                        ProjectDocument::getTitle,
                        ProjectDocument::getStatus,
                        ProjectDocument::getKind,
                        ProjectDocument::isEnabled,
                        ProjectDocument::getProjectGroupId,
                        ProjectDocument::getHomeCluster),
                all);
    }

    @ShellMethod(key = "project show", value = "Show a project.")
    public String show(
            @ShellOption(value = {"--tenant", "-T"}) String tenant,
            @ShellOption(value = {"--name", "-n"}) String name) {
        return projectService.findByTenantAndName(tenant, name)
                .map(ProjectCommands::renderOne)
                .orElse("Project '" + name + "' not found in tenant '" + tenant + "'.");
    }

    @ShellMethod(key = "project create", value = "Create a NORMAL project.")
    public String create(
            @ShellOption(value = {"--tenant", "-T"}) String tenant,
            @ShellOption(value = {"--name", "-n"}) String name,
            @ShellOption(value = {"--title", "-t"}, defaultValue = ShellOption.NULL) @Nullable String title,
            @ShellOption(value = {"--group", "-g"}, defaultValue = ShellOption.NULL) @Nullable String group,
            @ShellOption(value = {"--teams"}, defaultValue = ShellOption.NULL,
                    help = "Comma-separated team names")
            @Nullable String teams) {
        ProjectDocument project = projectService.create(tenant, name, title, group, parseList(teams));
        return "Created:\n" + renderOne(project);
    }

    @ShellMethod(key = "project update", value = "Update mutable fields of a project.")
    public String update(
            @ShellOption(value = {"--tenant", "-T"}) String tenant,
            @ShellOption(value = {"--name", "-n"}) String name,
            @ShellOption(value = {"--title", "-t"}, defaultValue = ShellOption.NULL) @Nullable String title,
            @ShellOption(value = {"--enabled"}, defaultValue = ShellOption.NULL) @Nullable Boolean enabled,
            @ShellOption(value = {"--group", "-g"}, defaultValue = ShellOption.NULL) @Nullable String group,
            @ShellOption(value = {"--clear-group"}, defaultValue = "false",
                    help = "Detach the project from any project group.")
            boolean clearGroup,
            @ShellOption(value = {"--teams"}, defaultValue = ShellOption.NULL,
                    help = "Comma-separated team names — replaces the list wholesale")
            @Nullable String teams) {
        ProjectDocument project = projectService.update(
                tenant, name, title, enabled, group, clearGroup, parseList(teams));
        return "Updated:\n" + renderOne(project);
    }

    @ShellMethod(key = "project close", value = "Close a project (soft-delete, lifecycle terminal).")
    public String close(
            @ShellOption(value = {"--tenant", "-T"}) String tenant,
            @ShellOption(value = {"--name", "-n"}) String name,
            @ShellOption(value = {"--closed-group"}, defaultValue = "_closed",
                    help = "Project-group bucket the closed project moves into.")
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

    @ShellMethod(key = "project suspend",
            value = "Stop engines + snapshot workspace + mark SUSPENDED. Brain-orchestrated.")
    public String suspend(
            @ShellOption(value = {"--tenant", "-T"}) String tenant,
            @ShellOption(value = {"--name", "-n"}) String name) {
        Response response = brainClient.post(
                tenant, "/brain/" + tenant + "/admin/projects/" + name + "/suspend", "{}");
        return formatResponse("Suspend", tenant, name, response);
    }

    @ShellMethod(key = "project resume",
            value = "Claim project for a pod, recover workspace, start engines, mark RUNNING.")
    public String resume(
            @ShellOption(value = {"--tenant", "-T"}) String tenant,
            @ShellOption(value = {"--name", "-n"}) String name) {
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
                + "  homeCluster: " + (p.getHomeCluster() == null ? "" : p.getHomeCluster()) + "\n"
                + "  claimedAt : " + (p.getClaimedAt() == null ? "" : p.getClaimedAt()) + "\n"
                + "  created   : " + (p.getCreatedAt() == null ? "" : p.getCreatedAt()) + "\n"
                + "  id        : " + (p.getId() == null ? "" : p.getId());
    }
}
