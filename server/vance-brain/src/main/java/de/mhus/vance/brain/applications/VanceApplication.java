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

    /**
     * Can this app take something a person wants to hand over — a link, a
     * quote, a document reference — as a new entry of its own?
     *
     * <p>This is the capability behind Milliways' {@code app} handler. "Send
     * this to my todos" is not an app, it is a capability that Kanban, GTD and
     * Issues share, so the declaration belongs to the app rather than to a
     * handler per app type. The {@link ShareIntake} is passed in so an app can
     * refuse what it cannot use — a link list has nothing to do with a subject
     * that carries no URL.
     *
     * <p>Type-level, not instance-level: whether one particular folder is
     * broken shows up when writing, not when offering the choice.
     *
     * <p>Default is {@code false} — apps opt in by overriding.
     */
    default boolean acceptsShare(ShareIntake intake) {
        return false;
    }

    /**
     * The places inside this app instance a caller can address: the pages of a
     * workbook, the columns of a board, the lists of a GTD folder.
     *
     * <p>The capability behind inter-app links. A link can point at a
     * <em>place</em> rather than only at a document, and the only thing that
     * knows what places an app has is the app. What a {@link AppTarget#handle}
     * means is the app's business — a document id, a slug, a column name; the
     * caller stores it opaquely and hands it back.
     *
     * <p><b>One method, two purposes.</b> {@link TargetPurpose} is in the
     * context rather than split across two methods, because for most apps the
     * answer is the same list. Where it is not — every workbook page can be
     * linked to, but a share does not create a page — the {@code if} belongs
     * here, in the app, and not in a caller that would have to know which apps
     * differ.
     *
     * <p>An empty list means "no places", and the link then addresses the app
     * itself. That also covers "linkable but takes nothing": a full
     * {@code NAVIGATE} list next to an empty {@code INTAKE} one needs no extra
     * flag. Whether a share is accepted at all stays with
     * {@link #acceptsShare} — that answers "this subject", not "where".
     *
     * <p>Default returns empty — apps opt in by overriding.
     */
    default List<AppTarget> targets(TargetsContext ctx) {
        return List.of();
    }

    /**
     * Take it. The app decides <em>where</em> — its own intake: the lead group
     * of a link list, the {@code inbox/} of a GTD folder, the backlog of an
     * issue tracker. A share is a hand-off, not an edit; refining happens in
     * the app, which is also where the sorting lives.
     *
     * <p>Authorization has already happened: the caller enforced
     * {@code Project WRITE} on the target project. The app writes through its
     * own service layer, as it does for every other write.
     *
     * @return {@link ShareIntakeResult#created} {@code false} when the thing
     *         was already there — that is a normal outcome, not a failure
     * @throws UnsupportedOperationException by default; only called for apps
     *         whose {@link #acceptsShare} said yes
     */
    default ShareIntakeResult acceptShare(ShareIntakeContext ctx) {
        throw new UnsupportedOperationException(appName() + " does not accept shares");
    }

    // ── Records ───────────────────────────────────────────────────

    /** Why a caller is asking for an app's places. See {@link #targets}. */
    enum TargetPurpose {
        /** Somewhere to point a link at. Read-only; every place qualifies. */
        NAVIGATE,
        /** Somewhere to put a new entry. Only places that accept one. */
        INTAKE
    }

    /**
     * One place inside an app instance.
     *
     * @param handle opaque to everyone but the app that produced it — a
     *               document id, a slug, a column name. It ends up in a stored
     *               link, so it should be the most stable identity the app has
     *               (an id over a title, wherever there is one).
     * @param label  what a human picks from
     * @param group  heading to sort under, purely visual; {@code null} for none
     */
    record AppTarget(String handle, String label, @Nullable String group) {

        /**
         * Separator of the Milliways app-share value ({@code project|path}),
         * which gains a third part when a share targets a place. A handle
         * carrying it would silently split into the wrong pieces there, so it
         * is rejected here — at the one place that produces handles — rather
         * than escaped at each of the several places that consume them.
         */
        private static final char RESERVED = '|';

        public AppTarget {
            if (handle == null || handle.isBlank()) {
                throw new IllegalArgumentException("AppTarget handle must not be blank");
            }
            if (handle.indexOf(RESERVED) >= 0) {
                throw new IllegalArgumentException(
                        "AppTarget handle must not contain '" + RESERVED + "': " + handle);
            }
            if (label == null || label.isBlank()) {
                throw new IllegalArgumentException(
                        "AppTarget label must not be blank (handle=" + handle + ")");
            }
        }

        public static AppTarget of(String handle, String label) {
            return new AppTarget(handle, label, null);
        }
    }

    /**
     * Plumbing for {@link #targets}. Same scope as {@link DescribeContext} plus
     * the {@link TargetPurpose} the caller is asking for.
     */
    record TargetsContext(
            String tenantId,
            String projectName,
            String folder,
            @Nullable String userId,
            TargetPurpose purpose,
            Map<String, Object> config) { }

    /**
     * What a share offers an app: a label, a URL, a quote, and whether a
     * document is behind it.
     *
     * <p>Deliberately <em>not</em> Milliways' own subject type. The app SPI
     * must not hang off the sharing subsystem — an app has to stay thinkable
     * without it — so the handler maps and the app stays unaware.
     */
    record ShareIntake(
            @Nullable String title,
            @Nullable String link,
            @Nullable String snippet,
            boolean hasDocument) {
    }

    /** Plumbing for {@link #acceptShare} — where it lands and who asked. */
    record ShareIntakeContext(
            String tenantId,
            String projectName,
            /** Folder of the target app instance, without the manifest name. */
            String folder,
            ShareIntake intake,
            /** The sharer's own remark, or {@code null}. */
            @Nullable String note,
            @Nullable String userId) {

        /**
         * The parts that do not fit into a title, as markdown: the sharer's
         * remark, the link, the snippet — in that order, whichever are there.
         *
         * <p>Here rather than in each app so three apps do not invent three
         * shapes for the same hand-off. The remark leads because it is the one
         * sentence a person wrote; the snippet is quoted because it describes
         * the page and is not the sharer speaking.
         */
        public String body() {
            StringBuilder out = new StringBuilder();
            if (note != null && !note.isBlank()) out.append(note.trim()).append("\n\n");
            if (intake.link() != null) out.append(intake.link()).append("\n\n");
            String snippet = intake.snippet();
            if (snippet != null && !snippet.isBlank()) {
                out.append("> ").append(snippet.trim().replace("\n", "\n> ")).append('\n');
            }
            return out.toString().stripTrailing();
        }
    }

    /**
     * @param created {@code false} when the entry was already there
     * @param label   what to call the app in the message the user reads
     */
    record ShareIntakeResult(boolean created, String label) {
    }

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
