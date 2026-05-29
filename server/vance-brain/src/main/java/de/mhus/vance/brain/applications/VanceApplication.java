package de.mhus.vance.brain.applications;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Contract for a Vance "application" — a self-contained domain
 * workspace identified by an {@code _app.yaml} manifest in a folder
 * (à la macOS {@code .app} bundles). Each implementation handles one
 * value of {@code $meta.app}: {@link CalendarsApplication} for
 * {@code app: calendar}, future {@code KanbanApplication} for
 * {@code app: kanban}, etc.
 *
 * <p>The interface is intentionally narrow — {@link #refresh} is the
 * single hot-path operation that the generic {@code app_rebuild} tool
 * dispatches to. App-specific finer-grained operations (e.g. just
 * regenerating the Gantt) live as additional methods on the concrete
 * implementation and are exposed via thin domain-specific tools
 * ({@code calendar_conflicts}, {@code gantt_from_calendars}, …).
 */
public interface VanceApplication {

    /** Discriminator that matches {@code $meta.app} in the manifest. */
    String appName();

    /**
     * Regenerate every derived artefact under the suite folder
     * ({@code _gantt.md}, {@code _conflicts.yaml}, …). Idempotent:
     * rerunning replaces the artefact bodies without touching the
     * source calendars.
     *
     * @throws de.mhus.vance.toolpack.ToolException when the folder
     *         can't be scanned (missing manifest, wrong app, parse
     *         errors in calendar files, etc.).
     */
    RefreshResult refresh(RefreshContext ctx);

    /**
     * Create the initial {@code _app.yaml} manifest for a new app
     * folder. The implementation owns the manifest schema — it
     * writes correct {@code $meta.kind / $meta.app} headers, the
     * nested {@code config.<app>} block, and any auto-default
     * structure (lanes, columns, sections, …).
     *
     * <p>This is the recommended entry point for new apps: LLMs
     * trying to write {@code _app.yaml} by hand routinely get
     * details wrong (flat lanes, wrong $meta keys). Calling
     * {@code create()} eliminates that whole class of failure —
     * the Java code, not the LLM, owns the manifest format.
     *
     * <p>Implementations should be idempotent on the manifest
     * file: if it already exists, overwrite or fail (per
     * {@link CreateContext#overwrite}). They should NOT pre-create
     * the lane / source files — those come later via domain tools
     * with the {@link CreateResult#lanes lane paths} from this
     * call.
     *
     * <p>Default implementation throws
     * {@link UnsupportedOperationException} — apps that haven't
     * opted into create-flow yet.
     */
    default CreateResult create(CreateContext ctx) {
        throw new UnsupportedOperationException(
                "Application '" + appName() + "' has no create() "
                        + "implementation yet — write the _app.yaml "
                        + "manifest by hand or pick another app type.");
    }

    // ── Records ───────────────────────────────────────────────────

    /**
     * Plumbing for {@link #refresh} — what tenant, what project,
     * which folder, who triggered it. The {@code processId} is
     * optional and lets the app emit progress updates back to the
     * caller's think-process when present.
     */
    record RefreshContext(
            String tenantId,
            String projectName,
            String folder,
            @Nullable String userId,
            @Nullable String processId) { }

    /**
     * Per-artefact outcome of a refresh. {@code path} is the
     * generated file's location, {@code markdownLink} is a ready-to-
     * paste chat link, {@code stats} are app-specific counts the LLM
     * (and the Web UI) can surface inline.
     */
    record ArtefactResult(
            String name,
            String path,
            @Nullable String markdownLink,
            Map<String, Object> stats) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("path", path);
            if (markdownLink != null) m.put("markdownLink", markdownLink);
            if (!stats.isEmpty()) m.put("stats", stats);
            return m;
        }
    }

    /** Bundle returned by {@link #refresh}. The map form is what
     *  {@code app_rebuild} surfaces to the LLM. */
    record RefreshResult(
            String app,
            String folder,
            List<ArtefactResult> artefacts) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("app", app);
            m.put("folder", folder);
            m.put("artefactCount", artefacts.size());
            List<Map<String, Object>> list = new java.util.ArrayList<>();
            for (ArtefactResult a : artefacts) list.add(a.toMap());
            m.put("artefacts", list);
            return m;
        }
    }

    // ── Create (initial setup) ────────────────────────────────────

    /**
     * Inputs for {@link #create}. {@code params} is the app-specific
     * payload (e.g. for calendar: lanes, title, window). The app
     * service interprets {@code params}; the foundation just passes
     * it through.
     */
    record CreateContext(
            String tenantId,
            String projectName,
            String folder,
            @Nullable String userId,
            @Nullable String processId,
            boolean overwrite,
            Map<String, Object> params) { }

    /**
     * Description of one configured lane/section/column the create
     * call produced. The {@code suggestedFilePath} is the path the
     * LLM should hand to the domain-create tool (e.g.
     * {@code calendar_create(outputPath=...)}) for the first source
     * file in this lane. Saves the LLM from guessing the sub-folder
     * convention.
     */
    record CreateLane(
            String name,
            @Nullable String title,
            @Nullable String color,
            String suggestedFilePath) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            if (title != null) m.put("title", title);
            if (color != null) m.put("color", color);
            m.put("suggestedFilePath", suggestedFilePath);
            return m;
        }
    }

    /** Outcome of {@link #create}. {@code artefacts} is populated
     *  when the create call also produced derived files (e.g. the
     *  calendar app auto-refreshes after dispatching inline events
     *  and ships the Gantt / Conflicts paths here). */
    record CreateResult(
            String app,
            String folder,
            String manifestPath,
            @Nullable String markdownLink,
            List<CreateLane> lanes,
            List<ArtefactResult> artefacts,
            @Nullable String nextStep,
            Map<String, Object> stats) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("app", app);
            m.put("folder", folder);
            m.put("manifestPath", manifestPath);
            if (markdownLink != null) m.put("markdownLink", markdownLink);
            List<Map<String, Object>> laneList = new java.util.ArrayList<>();
            for (CreateLane lane : lanes) laneList.add(lane.toMap());
            m.put("lanes", laneList);
            if (artefacts != null && !artefacts.isEmpty()) {
                List<Map<String, Object>> artList = new java.util.ArrayList<>();
                for (ArtefactResult a : artefacts) artList.add(a.toMap());
                m.put("artefacts", artList);
            }
            if (nextStep != null) m.put("nextStep", nextStep);
            if (!stats.isEmpty()) m.put("stats", stats);
            return m;
        }
    }
}
