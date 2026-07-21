package de.mhus.vance.brain.applications;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Contract for a Vance "application" — a self-contained domain
 * workspace identified by an {@code _app.yaml} manifest in a folder
 * (à la macOS {@code .app} bundles). Each implementation handles one
 * value of {@code $meta.app}. Built-in: {@link CalendarsApplication}
 * for {@code app: calendar}. First-party addons supply more
 * (slideshow, kanban, ...) via Spring auto-configuration.
 *
 * <p>The interface is intentionally narrow — {@link #refresh} is the
 * single hot-path operation that the generic {@code app_rebuild} tool
 * dispatches to. App-specific finer-grained operations (e.g. just
 * regenerating the Gantt) live as additional methods on the concrete
 * implementation and are exposed via thin domain-specific tools
 * ({@code calendar_conflicts}, {@code gantt_from_calendars}, …).
 */
public interface VanceApplication {

    /**
     * Filename of the per-folder manifest that identifies a folder as a
     * Vance application. The generic {@code app_rebuild} dispatcher and
     * the individual app implementations (slideshow, calendar, kanban,
     * ...) all look for this file inside the target folder.
     */
    String APP_MANIFEST = "_app.yaml";

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

    /**
     * Optional markdown snippet inserted into the chat-engine system
     * prompt while the user is viewing this app in their editor. Lets
     * each app surface its current state (lane names, column counts,
     * gantt output path, …) so the LLM can answer "what's in here" /
     * "add a task to the Backend lane" without scraping documents
     * upfront.
     *
     * <p>Engines call this on every turn that arrives with
     * {@link de.mhus.vance.api.thinkprocess.ActiveAppContext active-app}
     * metadata. The returned string is rendered raw inside the
     * {@code {% if activeApp %}} block of the engine prompt — keep it
     * short (a handful of lines), no need to repeat the engine's own
     * conventions. Return {@code null} when there's nothing useful to
     * say for this turn; the prompt block falls away cleanly.
     *
     * <p>Default returns {@code null} — apps opt in by overriding.
     */
    default @Nullable String promptInject(PromptInjectContext ctx) {
        return null;
    }

    /**
     * Cheap identity of this app instance for the Common Desktop card:
     * icon + open target. ALWAYS called by the desktop; keep it cheap
     * (manifest-level reads only — do NOT scan the folder). The icon
     * MAY reflect instance state (e.g. a "disabled" variant) when that
     * state is readable from the manifest.
     *
     * <p>Default returns a generic launcher card, so brand-new app
     * types show up on the desktop without opting in. The desktop and
     * the frontend never branch per app type — the icon string is
     * passed through verbatim (there is no fixed app set).
     */
    default AppCard describe(DescribeContext ctx) {
        return AppCard.defaults();
    }

    /**
     * Read-only snapshot of this app's dynamic state, for the Common
     * Desktop dashboard body (headline / metrics / items). MUST NOT
     * write documents or trigger side effects — unlike {@link #refresh}.
     *
     * <p>Return {@link Optional#empty()} when the app has no dynamic
     * body; the launcher card from {@link #describe} still renders.
     * The desktop guards this call (per-app try/catch + timeout), so a
     * slow or failing status only drops the body, never the card.
     *
     * <p>Default returns empty — apps opt in by overriding.
     */
    default Optional<AppStatus> status(StatusContext ctx) {
        return Optional.empty();
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
     * Plumbing for {@link #promptInject}. Carries the scope the engine
     * is asking about — tenant + project + the app folder root —
     * plus the originating session / process for telemetry. The app
     * implementation typically resolves its manifest under
     * {@code folder + "/_app.yaml"} via {@code DocumentService} and
     * formats whatever excerpt is useful.
     */
    record PromptInjectContext(
            String tenantId,
            String projectName,
            String folder,
            @Nullable String sessionId,
            @Nullable String processId,
            @Nullable String selection) { }

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

    // ── Desktop status (Common Desktop dashboard) ─────────────────

    /**
     * Plumbing for {@link #describe}. Carries the scope plus the
     * pre-parsed {@code config.<app>} block so the implementation can
     * decide icon / open-link without re-reading the manifest.
     */
    record DescribeContext(
            String tenantId,
            String projectName,
            String folder,
            @Nullable String userId,
            Map<String, Object> config) { }

    /**
     * Card identity for the Common Desktop. {@code icon} is a string the
     * frontend resolves generically — an emoji, a named token from the
     * shared icon set, or a {@code vance:}/{@code http} image link. The
     * app owns it; the desktop never maps icons per app type. A
     * {@code null} {@code openLink} lets the desktop build a default
     * deep-link from folder + app.
     */
    record AppCard(
            String icon,
            @Nullable String openLink) {

        /** Generic launcher icon for apps that don't override describe(). */
        public static AppCard defaults() {
            return new AppCard("📦", null);
        }
    }

    /**
     * Plumbing for {@link #status}. Same scope as {@link DescribeContext}
     * plus the originating think-process for telemetry.
     */
    record StatusContext(
            String tenantId,
            String projectName,
            String folder,
            @Nullable String userId,
            @Nullable String processId,
            Map<String, Object> config) { }

    /** Severity of an app status or item — drives the desktop card accent. */
    enum StatusSeverity {
        OK, ATTENTION, BLOCKED;

        public String wireName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** A small KPI chip on the desktop card. */
    record StatusMetric(String label, String value) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", label);
            m.put("value", value);
            return m;
        }
    }

    /**
     * One entry in the status body (a kanban card, a GTD task, …).
     * {@code deepLink} is an optional {@code vance:}-URI that jumps
     * into the app at this entry.
     */
    record StatusItem(
            String title,
            @Nullable String subtitle,
            @Nullable StatusSeverity severity,
            @Nullable String deepLink) {

        public static StatusItem of(String title) {
            return new StatusItem(title, null, null, null);
        }
    }

    /**
     * The dynamic body an app contributes to its desktop card.
     * {@code headline} is a one-liner ("3 in Doing"); {@code metrics}
     * are KPI chips; {@code items} are the actual entries.
     */
    record AppStatus(
            @Nullable String headline,
            StatusSeverity severity,
            List<StatusMetric> metrics,
            List<StatusItem> items,
            @Nullable Instant updatedAt) {

        public static AppStatus of(@Nullable String headline,
                                   StatusSeverity severity,
                                   List<StatusItem> items) {
            return new AppStatus(headline, severity, List.of(), items, null);
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
