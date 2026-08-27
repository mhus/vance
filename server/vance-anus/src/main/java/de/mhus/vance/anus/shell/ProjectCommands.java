package de.mhus.vance.anus.shell;

import de.mhus.vance.anus.access.RequiresAuth;
import de.mhus.vance.anus.brain.AnusBrainClient.Response;
import de.mhus.vance.anus.brain.AnusBrainClient;
import de.mhus.vance.shared.project.LifecycleType;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.project.maintenance.ProjectDataHandler;
import de.mhus.vance.shared.project.maintenance.ProjectMaintenanceService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import org.apache.commons.lang3.StringUtils;
import org.jline.reader.LineReader;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

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
    /**
     * Own instance — anus runs without web auto-configuration, so there is no
     * Jackson 3 mapper bean to inject. Same as {@code ProjectKitsCommands}.
     */
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

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
        return MaintenanceOutput.render(maintenanceService.inspect(tenant, name), "project");
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
                    description = "Proceed even though the project could not be drained off its"
                            + " pod. Only when the holder is known to be gone.",
                    defaultValue = "false")
            boolean force,
            @Option(longName = "no-drain",
                    description = "Do not hand the project off its pod first. Leaves engines"
                            + " running against data that is about to disappear.",
                    defaultValue = "false")
            boolean noDrain) {
        String problem = confirmed(name, confirm, "delete");
        if (problem != null) {
            return problem;
        }
        // Drain before the confirmation would be a side effect for a command the
        // operator may still abort; drain after it is the first thing that
        // happens.
        DrainStep drained = drainBefore(tenant, name, "delete", noDrain, force);
        if (drained.abort()) {
            return drained.log();
        }
        try {
            return drained.log()
                    + MaintenanceOutput.render(
                            maintenanceService.delete(tenant, name, force), "project");
        } catch (RuntimeException e) {
            return drained.log() + "Delete FAILED — " + e.getMessage();
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
                    description = "Proceed even though the project could not be drained off its"
                            + " pod.",
                    defaultValue = "false")
            boolean force,
            @Option(longName = "no-drain",
                    description = "Do not hand the project off its pod first, and do not place it"
                            + " again afterwards. The holding pod then keeps serving the old name.",
                    defaultValue = "false")
            boolean noDrain) {
        String problem = confirmed(name, confirm, "rename");
        if (problem != null) {
            return problem;
        }
        DrainStep drained = drainBefore(tenant, name, "rename", noDrain, force);
        if (drained.abort()) {
            return drained.log();
        }
        String result;
        try {
            result = MaintenanceOutput.render(maintenanceService.rename(tenant, name, to, force), "project");
        } catch (ProjectMaintenanceService.RenameBlockedException e) {
            return drained.log() + "Rename FAILED — nothing was written:\n  "
                    + String.join("\n  ", e.blockers())
                    + replaceHint(tenant, name, drained);
        } catch (RuntimeException e) {
            return drained.log() + "Rename FAILED — " + e.getMessage()
                    + replaceHint(tenant, name, drained);
        }
        // Put it back where it was, under the new name. Only when it was
        // actually placed: a project that nobody held has no state to restore,
        // and placing it here would start something the rename did not ask for.
        String replaced = drained.wasPlaced()
                ? "\n\nPlacing '" + to + "' again:\n" + claim(tenant, to)
                : "";
        return drained.log() + result
                + "\n\nReferences inside document content (vance: URIs, recipes, prompts)"
                + "\nare NOT rewritten — search for '" + name + "' if the project was linked to."
                + replaced;
    }

    /**
     * A drain that ran (or did not) ahead of a maintenance operation.
     *
     * @param abort whether the operation must not proceed
     * @param wasPlaced whether a pod held the project — the question that
     *     decides if a rename places it again afterwards
     * @param log lines to prefix the operation's own output with
     */
    private record DrainStep(boolean abort, boolean wasPlaced, String log) {}

    /**
     * Hands the project off its pod before a delete or rename.
     *
     * <p><b>Why this is the default and not an option.</b> A project on a pod is
     * being worked on: engines running, workspace mounted on that machine,
     * sessions open. Deleting or renaming underneath it does not fail loudly, it
     * leaves a process operating on data that no longer exists. Draining first
     * turns that into an orderly shutdown — and it does two more things worth
     * having:
     *
     * <ul>
     *   <li>The lease is gone afterwards, so the maintenance service's own guard
     *       passes without {@code --force}. Forcing becomes what it should be:
     *       the exception, for a holder that cannot be reached.</li>
     *   <li>The workspace is snapshotted into Mongo <em>by the pod that has
     *       it</em>. That is the only way a rename can carry a work area that
     *       lives on another machine's disk — the snapshot rows travel with the
     *       project, and the next placement recovers the folder under the new
     *       name.</li>
     * </ul>
     *
     * <p>A failed drain stops the operation unless {@code --force}: not knowing
     * whether a pod is still working on the project is exactly the situation
     * where proceeding is unsafe.
     */
    private DrainStep drainBefore(
            String tenant, String name, String operation, boolean noDrain, boolean force) {
        if (noDrain) {
            return new DrainStep(false, false,
                    "(--no-drain: the project was not handed off its pod)\n\n");
        }
        DrainOutcome outcome = drainOnce(tenant, name);
        if (outcome.released()) {
            return new DrainStep(false, outcome.placement() == Placement.PLACED,
                    outcome.message() + "\n\n");
        }
        if (force) {
            return new DrainStep(false, false,
                    "Drain failed, continuing because --force was given:\n  "
                            + outcome.message() + "\n\n");
        }
        return new DrainStep(true, false,
                "Refusing to " + operation + " — the project could not be handed off its pod:\n  "
                        + outcome.message()
                        + "\n\nFix the pod, or pass --force if the holder is known to be gone,"
                        + "\nor --no-drain to skip the hand-off entirely.");
    }

    /** Told after a failed rename, because the drain already happened. */
    private String replaceHint(String tenant, String name, DrainStep drained) {
        if (!drained.wasPlaced()) {
            return "";
        }
        return "\n\nThe project was drained off its pod and is still called '" + name
                + "' — run 'project claim -T " + tenant + " -n " + name + "' to place it again.";
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

    // ─── Placement lifecycle: where / claim / drain ─────────────────

    @Command(name = {"project", "where"},
            description = "Which pod currently holds the project, if any.")
    public String where(
            @Option(longName = "tenant", shortName = 'T', required = true) String tenant,
            @Option(longName = "name", shortName = 'n', required = true) String name) {
        Response response = brainClient.internal(homePath(tenant, name), "GET", null);
        if (response.statusCode() == 404) {
            return "not placed — " + response.body();
        }
        if (!response.isSuccess()) {
            return "(HTTP " + response.statusCode() + " " + response.body() + ")";
        }
        return response.body();
    }

    @Command(name = {"project", "claim"},
            description = "Place the project on a suitable pod — full placement with pod "
                    + "search, NOT a local claim. Reports why if it cannot be placed.")
    public String claim(
            @Option(longName = "tenant", shortName = 'T', required = true) String tenant,
            @Option(longName = "name", shortName = 'n', required = true) String name) {
        // Deliberately not `project resume`: that one calls bring() on whichever
        // pod answers the REST call, so it means "start it here". This asks the
        // placement service, so the labels and the load decide.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenantId", tenant);
        payload.put("projectName", name);
        Response response = brainClient.internal(
                "/internal/cluster/place", "POST", objectMapper.writeValueAsString(payload));
        return switch (response.statusCode()) {
            case 200 -> "placed: " + response.body();
            // Each of these is a different situation and a different next step,
            // which is the whole reason the endpoint distinguishes them.
            case 409 -> "already running: " + response.body();
            case 503 -> "cannot be placed: " + response.body()
                    + "\n(NO_ELIGIBLE_POD → provide a pod with matching labels; "
                    + "NO_CAPACITY → the matching pods are full)";
            case 502 -> "a pod was chosen but the bring failed: " + response.body();
            case 404 -> "no such project";
            default -> "(HTTP " + response.statusCode() + " " + response.body() + ")";
        };
    }

    @Command(name = {"project", "drain"},
            description = "Hand the project off its current pod: stop engines, snapshot the "
                    + "workspace, drop the lease. Status stays RUNNING.")
    public String drain(
            @Option(longName = "tenant", shortName = 'T', required = true) String tenant,
            @Option(longName = "name", shortName = 'n', required = true) String name,
            @Option(longName = "place",
                    description = "After draining, immediately place it again. Only useful "
                            + "once this pod has been made ineligible — see the note below.",
                    defaultValue = "false")
            boolean place) {
        DrainOutcome outcome = drainOnce(tenant, name);
        if (!outcome.released()) {
            return outcome.message();
        }
        if (outcome.placement() == Placement.NOT_PLACED) {
            return outcome.message();
        }
        if (!place) {
            // Said every time, because it is the part that surprises: this pod is
            // still eligible and often the least loaded, so the next placement
            // may well hand the project straight back.
            return outcome.message() + "\n(this pod is still eligible — make it ineligible first "
                    + "(cluster pod exclusive / label-set) or it may take the project back)";
        }
        return outcome.message() + "\n" + claim(tenant, name);
    }

    /** Whether the project was on a pod when we looked. */
    private enum Placement {
        /** Nobody held it — there was nothing to hand off. */
        NOT_PLACED,
        /** A pod held it and we reached that pod. */
        PLACED,
        /** We could not find out — unreachable brain, or the lease moved. */
        UNKNOWN
    }

    /**
     * One drain attempt, as facts rather than as a message.
     *
     * @param released whether the project is now owned by nobody, as far as this
     *     attempt can tell. {@code false} means the hand-off did not happen —
     *     the caller decides whether that stops it.
     */
    private record DrainOutcome(Placement placement, boolean released, String message) {}

    /**
     * Hands the project off its pod: stop engines, snapshot the workspace, drop
     * the lease. Shared by {@code project drain} and by delete/rename, which
     * need the same hand-off but a different reaction to it.
     *
     * <p>Two steps, and the first one is not ours: the release has to reach the
     * holding pod, because it tears down in-memory state that exists only
     * there. Asking the brain where that is beats teaching this CLI the lease
     * TTL it cannot see.
     */
    private DrainOutcome drainOnce(String tenant, String name) {
        Response home = brainClient.internal(homePath(tenant, name), "GET", null);
        if (home.statusCode() == 404) {
            return new DrainOutcome(Placement.NOT_PLACED, true,
                    "nothing to drain — " + home.body());
        }
        if (!home.isSuccess()) {
            return new DrainOutcome(Placement.UNKNOWN, false,
                    "(cannot resolve the home pod: HTTP " + home.statusCode()
                            + " " + home.body() + ")");
        }
        String endpoint;
        String nodeName;
        try {
            var parsed = objectMapper.readTree(home.body());
            endpoint = parsed.get("endpoint").asString();
            nodeName = parsed.has("nodeName") ? parsed.get("nodeName").asString() : endpoint;
        } catch (RuntimeException e) {
            return new DrainOutcome(Placement.UNKNOWN, false,
                    "(unreadable home-pod response: " + home.body() + ")");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenantId", tenant);
        payload.put("projectName", name);
        String body = objectMapper.writeValueAsString(payload);
        Response released = brainClient.internalAt(
                normaliseBase(endpoint), "/internal/cluster/release", "POST", body);
        if (released.statusCode() == 409) {
            // The lease moved or expired between the lookup and here. Not a
            // clean hand-off and not a safe "nobody owns it": another pod may
            // have taken it. Reported as unknown so a caller that needs the
            // project quiet stops rather than guessing.
            return new DrainOutcome(Placement.UNKNOWN, false,
                    "pod '" + nodeName + "' does not hold it (any more) — nothing drained,"
                            + " retry to reach the current holder");
        }
        if (!released.isSuccess()) {
            return new DrainOutcome(Placement.PLACED, false,
                    "(drain failed on '" + nodeName + "': HTTP " + released.statusCode()
                            + " " + released.body() + ")");
        }
        return new DrainOutcome(Placement.PLACED, true,
                "drained from '" + nodeName + "' — status unchanged, nobody owns it now");
    }

    private static String homePath(String tenant, String name) {
        return "/internal/cluster/projects/home?tenantId=" + tenant + "&projectName=" + name;
    }

    /** {@code host:port} from a pod row to an absolute URL. */
    private static String normaliseBase(String endpoint) {
        return endpoint.startsWith("http://") || endpoint.startsWith("https://")
                ? endpoint : "http://" + endpoint;
    }

    // ─── Placement selector ─────────────────────────────────────────
    //
    // Over REST like lifecycle-type, not straight to ProjectService: the brain
    // owns the write, and PodSelector.validate sits on that path. A selector
    // written past it is one no pod label can ever match.
    //
    // Via /internal/**, not the tenant admin route, although both exist and do
    // the same thing. The admin route belongs to a tenant administrator and is
    // reachable from the Web-UI with a user token; this one belongs to the
    // infrastructure actor that also labels the pods, which holds one
    // credential for the whole cluster rather than one per tenant. anus is that
    // actor.

    @Command(name = {"project", "placement"},
            description = "Show or set what a project requires of a pod: its placement "
                    + "selector (matched against pod labels) and its resource score.")
    public String placement(
            @Option(longName = "tenant", shortName = 'T', required = true) String tenant,
            @Option(longName = "name", shortName = 'n', required = true) String name,
            @Option(longName = "selector", shortName = 's',
                    description = "Comma-separated k=v pairs. REPLACES the whole selector; "
                            + "use --add / --rm to change single entries. Omit to show.")
            @Nullable String selector,
            @Option(longName = "add",
                    description = "Comma-separated k=v pairs to set, keeping the rest.")
            @Nullable String add,
            @Option(longName = "rm",
                    description = "Comma-separated keys to remove, keeping the rest.")
            @Nullable String rm,
            @Option(longName = "clear",
                    description = "Remove the whole selector — the project fits any pod again.",
                    defaultValue = "false")
            boolean clear,
            @Option(longName = "score",
                    description = "New homeResourceScore, at least 0.")
            @Nullable String score) {
        ProjectDocument current = projectService.findByTenantAndName(tenant, name)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No project '" + name + "' in tenant '" + tenant + "'"));

        int mutators = (selector != null ? 1 : 0) + (add != null ? 1 : 0)
                + (rm != null ? 1 : 0) + (clear ? 1 : 0);
        if (mutators > 1) {
            return "(--selector, --add, --rm and --clear are mutually exclusive)";
        }
        if (mutators == 0 && StringUtils.isBlank(score)) {
            return renderPlacement(current);
        }

        Integer parsedScore = null;
        if (!StringUtils.isBlank(score)) {
            // Parsed here for the same reason lifecycle-type parses its value:
            // a bad argument must come back naming the option, not as an
            // unexplained 400 from the far end.
            try {
                parsedScore = Integer.valueOf(score.trim());
            } catch (NumberFormatException e) {
                return "(--score must be an integer, got '" + score + "')";
            }
            if (parsedScore < 0) {
                return "(--score must not be negative)";
            }
        }

        Map<String, String> target = null;
        List<String> missing = List.of();
        if (clear) {
            target = new TreeMap<>();
        } else if (selector != null) {
            target = parsePairs(selector);
        } else if (add != null) {
            // Read-modify-write against an endpoint that replaces the whole map:
            // a concurrent write between read and send is lost. Fine for an
            // interactive shell, and the reason --selector exists for anything
            // that reconciles a desired state.
            target = new TreeMap<>(currentSelector(current));
            target.putAll(parsePairs(add));
        } else if (rm != null) {
            target = new TreeMap<>(currentSelector(current));
            List<String> notFound = new ArrayList<>();
            for (String key : splitCsv(rm)) {
                if (target.remove(key) == null) notFound.add(key);
            }
            missing = notFound;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenantId", tenant);
        payload.put("projectName", name);
        if (target != null) payload.put("placementSelector", target);
        if (parsedScore != null) payload.put("homeResourceScore", parsedScore);

        Response response = brainClient.internal(
                "/internal/cluster/projects/placement", "POST",
                objectMapper.writeValueAsString(payload));
        if (!response.isSuccess()) {
            return "(failed: HTTP " + response.statusCode() + " " + response.body() + ")";
        }
        String out = response.body();
        // Reported, not an error: removing a key that is not there reaches the
        // desired state, and failing would make the command non-idempotent.
        return missing.isEmpty() ? out
                : out + "\n(no such selector key, nothing removed: "
                        + String.join(", ", missing) + ")";
    }

    private static Map<String, String> currentSelector(ProjectDocument project) {
        Map<String, String> selector = project.getPlacementSelector();
        return selector == null ? Map.of() : selector;
    }

    /**
     * The read half. Names {@code pendingSince} explicitly rather than leaving
     * it out: a project with a selector nothing satisfies looks identical to a
     * correctly placed one in every other field.
     */
    private static String renderPlacement(ProjectDocument project) {
        Map<String, String> selector = new TreeMap<>(currentSelector(project));
        StringBuilder out = new StringBuilder();
        out.append("project    ").append(project.getTenantId()).append('/')
                .append(project.getName()).append('\n');
        out.append("selector   ").append(selector.isEmpty()
                ? "(none — fits any pod that is not exclusive)"
                : selector.entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue())
                        .collect(java.util.stream.Collectors.joining(","))).append('\n');
        out.append("score      ").append(project.getHomeResourceScore()).append('\n');
        out.append("status     ").append(project.getStatus()).append('\n');
        out.append("homeNode   ").append(project.getHomeNode() == null
                ? "— (nobody owns it)" : project.getHomeNode()).append('\n');
        if (project.getPendingSince() != null) {
            out.append("WAITING    since ").append(project.getPendingSince())
                    .append(" — no pod matched at the last attempt\n");
        }
        return out.toString();
    }

    /** Comma-separated {@code k=v} pairs to a map. */
    private static Map<String, String> parsePairs(@Nullable String csv) {
        Map<String, String> out = new TreeMap<>();
        for (String pair : splitCsv(csv)) {
            int eq = pair.indexOf('=');
            if (eq <= 0 || eq == pair.length() - 1) {
                throw new IllegalArgumentException("Expected k=v, got '" + pair + "'");
            }
            out.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
        return out;
    }

    private static List<String> splitCsv(@Nullable String csv) {
        if (StringUtils.isBlank(csv)) return List.of();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
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
