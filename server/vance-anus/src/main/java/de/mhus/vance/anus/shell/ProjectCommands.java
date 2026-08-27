package de.mhus.vance.anus.shell;

import de.mhus.vance.anus.access.RequiresAuth;
import de.mhus.vance.anus.brain.AnusBrainClient;
import de.mhus.vance.anus.brain.AnusBrainClient.Response;
import de.mhus.vance.shared.project.LifecycleType;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.project.maintenance.ProjectDataHandler;
import de.mhus.vance.shared.project.maintenance.ProjectMaintenanceReport;
import de.mhus.vance.shared.project.maintenance.ProjectMaintenanceReport.EntityResult;
import de.mhus.vance.shared.project.maintenance.ProjectMaintenanceReport.UnaccountedCollection;
import de.mhus.vance.shared.project.maintenance.ProjectMaintenanceService;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import org.apache.commons.lang3.StringUtils;
import org.jline.reader.LineReader;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

/**
 * CRUD over {@link ProjectDocument}, plus the two service tasks that touch
 * everything a project owns: {@code project delete} and {@code project rename}.
 *
 * <p><b>Delete is not the end of the lifecycle, it is the end of the project.</b>
 * {@code project close} remains what a running system does — status
 * {@code CLOSED}, workspace disposed, the project still there to be looked at.
 * Delete removes it and its data across every collection, which is why it is
 * gated twice (live lease, typed confirmation) and why {@code project inspect}
 * exists to show what would go before anything does.
 */
@Component
@RequiresAuth
public class ProjectCommands {

    private final ProjectService projectService;
    private final AnusBrainClient brainClient;
    private final ProjectMaintenanceService maintenanceService;
    // Lazy LineReader to avoid the Spring-Shell bean cycle — see AccessCommands.
    private final ObjectProvider<LineReader> lineReader;

    public ProjectCommands(
            ProjectService projectService,
            AnusBrainClient brainClient,
            ProjectMaintenanceService maintenanceService,
            ObjectProvider<LineReader> lineReader) {
        this.projectService = projectService;
        this.brainClient = brainClient;
        this.maintenanceService = maintenanceService;
        this.lineReader = lineReader;
    }

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

    // ─── Service tasks across every entity ─────────────────────────────────
    //
    // All three go through ProjectMaintenanceService, which asks one
    // ProjectDataHandler per entity. Adding a project-scoped collection to the
    // system means adding a handler; nothing here has to change, and a
    // collection without one shows up in the "unaccounted" section rather than
    // being silently left behind.

    @Command(name = {"project", "inspect"},
            description = "Count everything a project owns, per entity. Writes nothing.")
    public String inspect(
            @Option(longName = "tenant", shortName = 'T', required = true) String tenant,
            @Option(longName = "name", shortName = 'n', required = true) String name) {
        if (projectService.findByTenantAndName(tenant, name).isEmpty()) {
            return "Project '" + name + "' not found in tenant '" + tenant + "'.";
        }
        return render(maintenanceService.inspect(tenant, name));
    }

    @Command(name = {"project", "handlers"},
            description = "List the entities this process can delete or rename.")
    public String handlers() {
        // The honest answer to "will a delete here be complete?". A handler is
        // a bean, so the list depends on what this process has loaded — an
        // addon that is not installed contributes nothing, and its data would
        // stay behind.
        return Tables.render(
                List.of("ORDER", "ENTITY", "COLLECTIONS"),
                List.<Function<ProjectDataHandler, @Nullable Object>>of(
                        ProjectDataHandler::order,
                        ProjectDataHandler::id,
                        h -> String.join(",", h.collections())),
                maintenanceService.handlers());
    }

    @Command(name = {"project", "delete"},
            description = "Hard-delete a project and all its data. Irreversible.")
    public String delete(
            @Option(longName = "tenant", shortName = 'T', required = true) String tenant,
            @Option(longName = "name", shortName = 'n', required = true) String name,
            @Option(longName = "confirm",
                    description = "Type the project name here to confirm. Required when there"
                            + " is no terminal to ask on (--sudo).")
            @Nullable String confirm,
            @Option(longName = "force",
                    description = "Proceed even though a pod still holds the project's lease."
                            + " Only when the holder is known to be gone.",
                    defaultValue = "false")
            boolean force) {
        String problem = confirmed(name, confirm, "delete");
        if (problem != null) {
            return problem;
        }
        try {
            return render(maintenanceService.delete(tenant, name, force));
        } catch (RuntimeException e) {
            return "Delete FAILED — " + e.getMessage();
        }
    }

    @Command(name = {"project", "rename"},
            description = "Rename a project, carrying every structured reference with it.")
    public String rename(
            @Option(longName = "tenant", shortName = 'T', required = true) String tenant,
            @Option(longName = "name", shortName = 'n', required = true) String name,
            @Option(longName = "to", required = true,
                    description = "The new project name.") String to,
            @Option(longName = "confirm",
                    description = "Type the current project name here to confirm. Required when"
                            + " there is no terminal to ask on (--sudo).")
            @Nullable String confirm,
            @Option(longName = "force",
                    description = "Proceed even though a pod still holds the project's lease.",
                    defaultValue = "false")
            boolean force) {
        String problem = confirmed(name, confirm, "rename");
        if (problem != null) {
            return problem;
        }
        try {
            return render(maintenanceService.rename(tenant, name, to, force))
                    + "\n\nReferences inside document content (vance: URIs, recipes, prompts)"
                    + "\nare NOT rewritten — search for '" + name + "' if the project was linked to.";
        } catch (ProjectMaintenanceService.RenameBlockedException e) {
            return "Rename FAILED — nothing was written:\n  "
                    + String.join("\n  ", e.blockers());
        } catch (RuntimeException e) {
            return "Rename FAILED — " + e.getMessage();
        }
    }

    /**
     * The typed confirmation, or the reason it did not happen.
     *
     * <p>Two shapes because there are two callers. At a terminal the operator
     * is asked and types the name — the point being to make the hand pause on
     * the <em>right</em> project, which a {@code yes/no} prompt does not do.
     * Under {@code --sudo} there is nobody to ask, so the same string has to
     * arrive as {@code --confirm}; refusing rather than assuming keeps a
     * scripted delete from being one typo away from the wrong project.
     */
    private @Nullable String confirmed(String name, @Nullable String confirm, String operation) {
        String answer = confirm;
        if (StringUtils.isBlank(answer)) {
            LineReader reader = lineReader.getIfAvailable();
            if (reader == null) {
                return "Refusing to " + operation + " without confirmation — pass --confirm "
                        + name;
            }
            answer = reader.readLine(
                    "Type the project name '" + name + "' to confirm the " + operation + ": ");
        }
        if (!name.equals(answer == null ? null : answer.trim())) {
            return "Confirmation did not match '" + name + "' — nothing was done.";
        }
        return null;
    }

    private static String render(ProjectMaintenanceReport report) {
        String header = switch (report.operation()) {
            case INSPECT -> "Contents of project '" + report.projectId() + "' in tenant '"
                    + report.tenantId() + "':";
            case DELETE -> "Deleted project '" + report.projectId() + "' in tenant '"
                    + report.tenantId() + "':";
            case RENAME -> "Renamed project '" + report.projectId() + "' in tenant '"
                    + report.tenantId() + "':";
        };
        String table = Tables.render(
                List.of("ENTITY", "ROWS", "COLLECTIONS", "NOTE"),
                List.<Function<EntityResult, @Nullable Object>>of(
                        EntityResult::handlerId,
                        EntityResult::affected,
                        e -> String.join(",", e.collections()),
                        EntityResult::note),
                report.entities());
        StringBuilder out = new StringBuilder(header)
                .append('\n').append(table)
                .append("\n  total: ").append(report.total());
        if (report.hasUnaccounted()) {
            out.append("\n\nWARNING — collections holding rows for this project that no handler"
                    + "\nclaims. They were NOT touched; add a ProjectDataHandler for each:");
            for (UnaccountedCollection unaccounted : report.unaccounted()) {
                out.append("\n  ").append(unaccounted.collection())
                        .append(": ").append(unaccounted.count()).append(" row(s)");
            }
        }
        return out.toString();
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

    @Command(name = {"project", "lifecycle-type"},
            description = "Set AUTO (derived) | EPHEMERAL (never auto-start) | PERMANENT (always placed).")
    public String lifecycleType(
            @Option(longName = "tenant", shortName = 'T', required = true) String tenant,
            @Option(longName = "name", shortName = 'n', required = true) String name,
            @Option(longName = "value", shortName = 'v', required = true) String value) {
        // Parsed here rather than concatenated into the body: an unknown or
        // quote-carrying value would otherwise travel as malformed JSON and
        // come back as an unexplained 400 instead of a CLI message naming
        // the choices.
        LifecycleType parsed;
        try {
            parsed = LifecycleType.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return "Set lifecycle-type FAILED — unknown value '" + value.trim()
                    + "'. Expected one of: " + Arrays.toString(LifecycleType.values());
        }
        // Brain-orchestrated like suspend/resume: the value decides whether the
        // cluster keeps the project placed, so the Brain has to see the change
        // rather than find it later in the document.
        Response response = brainClient.post(
                tenant, "/brain/" + tenant + "/admin/projects/" + name + "/lifecycle-type",
                "{\"lifecycleType\":\"" + parsed.name() + "\"}");
        return formatResponse("Set lifecycle-type", tenant, name, response);
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
                + "  lifecycle : " + p.getLifecycleType()
                + (p.isOwnerRequired() ? " (ownerRequired)" : "") + "\n"
                + "  homeNode  : " + (p.getHomeNode() == null ? "" : p.getHomeNode()) + "\n"
                + "  leaseSeen : " + (p.getClaimedAt() == null ? "" : p.getClaimedAt()) + "\n"
                + "  created   : " + (p.getCreatedAt() == null ? "" : p.getCreatedAt()) + "\n"
                + "  id        : " + (p.getId() == null ? "" : p.getId());
    }
}
