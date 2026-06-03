package de.mhus.vance.addon.brain.calendar;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.kind.ApplicationCodec;
import de.mhus.vance.shared.document.kind.ApplicationDocument;
import de.mhus.vance.toolpack.ToolException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Shared folder-scan logic used by all calendar-suite tools
 * ({@code calendar_aggregate}, {@code calendar_conflicts},
 * {@code gantt_from_calendars}, {@code app_rebuild}).
 *
 * <p>Resolves the {@code _app.yaml} manifest, lists every
 * {@code kind: calendar} document under the folder, and tags each
 * one with the lane it belongs to (= leaf folder name relative to
 * the suite root). Generated artefacts ({@code _gantt.md},
 * {@code _conflicts.yaml}) and the manifest itself are excluded
 * from the calendar list.
 */
@Service
public class CalendarFolderReader {

    /** File-name of the application manifest at the folder root. */
    public static final String APP_MANIFEST = "_app.yaml";

    /** Generated artefacts that should never show up as calendars. */
    private static final List<String> GENERATED_LEAF_NAMES = List.of(
            "_gantt.md", "_conflicts.yaml", "_app.yaml", "_info.yaml");

    /** Default lane for calendar files that sit directly in the root
     *  folder (no sub-folder = no explicit lane). */
    public static final String DEFAULT_LANE = "default";

    /** A calendar file plus its resolved lane name and parsed body. */
    public record CalendarFile(
            DocumentDocument doc,
            String lane,
            CalendarDocument calendar) { }

    /** Bundle of everything a tool needs after scanning the folder. */
    public record Scan(
            String folder,
            DocumentDocument manifestDoc,
            ApplicationDocument manifest,
            CalendarsAppConfig calendarConfig,
            List<CalendarFile> calendars) {

        /**
         * Flat list of {@code (event, lane, sourcePath)} triples,
         * unfiltered (no recurrence expansion, no date-range cut).
         */
        public List<EventRef> allEvents() {
            List<EventRef> out = new ArrayList<>();
            for (CalendarFile cf : calendars) {
                for (CalendarEvent ev : cf.calendar().events()) {
                    out.add(new EventRef(ev, cf.lane(), cf.doc().getPath()));
                }
            }
            return out;
        }
    }

    /** A single event together with where it came from. */
    public record EventRef(CalendarEvent event, String lane, String sourcePath) { }

    private final DocumentService documentService;

    public CalendarFolderReader(DocumentService documentService) {
        this.documentService = documentService;
    }

    /**
     * Read the full state of a calendar-app folder: manifest +
     * calendars + lane assignments + typed config view.
     *
     * @throws ToolException when the manifest is missing or
     *         configured for a different app ({@code app != "calendar"}).
     */
    public Scan scan(String tenantId, String projectName, String folder) {
        String normalisedFolder = normaliseFolder(folder);
        DocumentDocument manifestDoc = loadManifest(tenantId, projectName, normalisedFolder);
        ApplicationDocument manifest = parseManifest(manifestDoc);
        if (!CalendarsAppConfig.APP_NAME.equalsIgnoreCase(manifest.app())) {
            throw new ToolException(
                    "Folder '" + normalisedFolder + "' is an "
                            + (manifest.app().isBlank() ? "untyped" : manifest.app())
                            + " application — expected 'calendar'. "
                            + "Edit '" + normalisedFolder + "/_app.yaml' "
                            + "and set `$meta.app: calendar`.");
        }
        CalendarsAppConfig calendarConfig = CalendarsAppConfig.from(manifest);

        List<CalendarFile> calendars = loadCalendars(
                tenantId, projectName, normalisedFolder);
        return new Scan(normalisedFolder, manifestDoc, manifest,
                calendarConfig, calendars);
    }

    /** Test/CLI escape hatch: scan a folder where the manifest is
     *  optional. Returns auto-defaults for the config. Throws when
     *  manifest is present but for a non-calendar app. */
    public Scan scanOptional(String tenantId, String projectName, String folder) {
        String normalisedFolder = normaliseFolder(folder);
        Optional<DocumentDocument> manifestOpt = documentService.findByPath(
                tenantId, projectName, normalisedFolder + "/" + APP_MANIFEST);
        DocumentDocument manifestDoc;
        ApplicationDocument manifest;
        CalendarsAppConfig calendarConfig;
        if (manifestOpt.isPresent()) {
            manifestDoc = manifestOpt.get();
            manifest = parseManifest(manifestDoc);
            if (!CalendarsAppConfig.APP_NAME.equalsIgnoreCase(manifest.app())) {
                throw new ToolException(
                        "Folder '" + normalisedFolder + "' is an "
                                + manifest.app() + " app, expected 'calendar'.");
            }
            calendarConfig = CalendarsAppConfig.from(manifest);
        } else {
            manifestDoc = null;
            manifest = ApplicationDocument.empty(CalendarsAppConfig.APP_NAME);
            calendarConfig = CalendarsAppConfig.from(new LinkedHashMap<>());
        }
        List<CalendarFile> calendars = loadCalendars(
                tenantId, projectName, normalisedFolder);
        return new Scan(normalisedFolder, manifestDoc, manifest,
                calendarConfig, calendars);
    }

    // ── Manifest ──────────────────────────────────────────────────

    private DocumentDocument loadManifest(String tenantId, String projectName, String folder) {
        String path = folder + "/" + APP_MANIFEST;
        return documentService.findByPath(tenantId, projectName, path)
                .orElseThrow(() -> new ToolException(
                        "No _app.yaml manifest found at '" + path
                                + "'. Use `calendar_app_create` to "
                                + "bootstrap a new calendar app — "
                                + "writing the manifest by hand is "
                                + "error-prone."));
    }

    private ApplicationDocument parseManifest(DocumentDocument doc) {
        String body = loadAsText(doc);
        String mime = doc.getMimeType();
        if (!ApplicationCodec.supports(mime)) {
            throw new ToolException(
                    "Manifest '" + doc.getPath() + "' has mime '"
                            + mime + "' — must be JSON or YAML.");
        }
        ApplicationDocument parsed;
        try {
            parsed = ApplicationCodec.parse(body, mime);
        } catch (Exception e) {
            throw new ToolException(
                    "Could not parse manifest '" + doc.getPath()
                            + "': " + e.getMessage());
        }

        // Reject manifests that don't actually carry a $meta.kind of
        // 'application' — the codec defaults to "application" when the
        // field is missing, which silently lets a malformed manifest
        // through. We use the DB-mirrored DocumentDocument.kind (which
        // *only* gets set when $meta.kind was present in the body)
        // as the authoritative signal.
        String dbKind = doc.getKind();
        if (dbKind == null || dbKind.isBlank()) {
            throw new ToolException(
                    "Manifest '" + doc.getPath() + "' is missing "
                            + "`$meta.kind: application`. Without it "
                            + "the file is treated as plain YAML, "
                            + "not as an app manifest. Recreate the "
                            + "app via `calendar_app_create` or add "
                            + "the `$meta` header manually:\n"
                            + "  $meta:\n"
                            + "    kind: application\n"
                            + "    app:  calendar");
        }
        if (!"application".equalsIgnoreCase(dbKind)) {
            throw new ToolException(
                    "Manifest '" + doc.getPath() + "' has "
                            + "`$meta.kind: " + dbKind + "`, expected "
                            + "'application'. This document is not "
                            + "an app manifest.");
        }
        if (parsed.app() == null || parsed.app().isBlank()) {
            throw new ToolException(
                    "Manifest '" + doc.getPath() + "' is missing "
                            + "`$meta.app`. The discriminator is "
                            + "required so app-specific tools know "
                            + "which service to dispatch to (e.g. "
                            + "`app: calendar` for a calendar suite).");
        }
        return parsed;
    }

    // ── Calendars ─────────────────────────────────────────────────

    private List<CalendarFile> loadCalendars(String tenantId, String projectName, String folder) {
        // The kind: calendar index gives us every calendar in the
        // project; we filter by path prefix to limit to this app.
        // Generated artefacts (_gantt.md, _conflicts.yaml, _app.yaml)
        // are excluded by leaf-name so the scan stays idempotent
        // even when they live under the same prefix.
        List<DocumentDocument> all = documentService.listByKind(
                tenantId, projectName, "calendar");
        List<CalendarFile> out = new ArrayList<>();
        String prefix = folder + "/";
        for (DocumentDocument d : all) {
            String path = d.getPath();
            if (path == null || !path.startsWith(prefix)) continue;
            if (isGeneratedArtefactPath(path)) continue;
            String lane = laneFor(folder, path);
            CalendarDocument cal = parseCalendar(d);
            out.add(new CalendarFile(d, lane, cal));
        }
        return out;
    }

    private CalendarDocument parseCalendar(DocumentDocument doc) {
        String body = loadAsText(doc);
        String mime = doc.getMimeType();
        if (!CalendarCodec.supports(mime)) {
            throw new ToolException(
                    "Calendar file '" + doc.getPath() + "' has mime '"
                            + mime + "' — must be JSON or YAML.");
        }
        try {
            return CalendarCodec.parse(body, mime);
        } catch (Exception e) {
            throw new ToolException(
                    "Could not parse calendar '" + doc.getPath()
                            + "': " + e.getMessage());
        }
    }

    private String loadAsText(DocumentDocument doc) {
        if (doc.getInlineText() != null) return doc.getInlineText();
        try (InputStream in = documentService.loadContent(doc)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolException(
                    "Could not read '" + doc.getPath() + "': " + e.getMessage());
        }
    }

    // ── Lane resolution ───────────────────────────────────────────

    /**
     * Compute the lane for a calendar document. Rule (deterministic):
     * the leaf folder of the file relative to the suite root.
     * Files directly in the suite root get {@link #DEFAULT_LANE}.
     */
    public static String laneFor(String suiteFolder, String filePath) {
        String relative = filePath.substring(suiteFolder.length() + 1);
        int slash = relative.lastIndexOf('/');
        if (slash < 0) return DEFAULT_LANE;
        String parent = relative.substring(0, slash);
        int innerSlash = parent.lastIndexOf('/');
        return innerSlash < 0 ? parent : parent.substring(innerSlash + 1);
    }

    static boolean isGeneratedArtefactPath(String path) {
        int slash = path.lastIndexOf('/');
        String leaf = slash < 0 ? path : path.substring(slash + 1);
        return GENERATED_LEAF_NAMES.contains(leaf);
    }

    private static String normaliseFolder(@Nullable String folder) {
        if (folder == null || folder.isBlank()) {
            throw new ToolException("folder must be provided");
        }
        String f = folder.trim();
        while (f.endsWith("/")) f = f.substring(0, f.length() - 1);
        while (f.startsWith("/")) f = f.substring(1);
        if (f.isEmpty()) throw new ToolException("folder must not be empty");
        return f;
    }

    /** Utility for tools that need to write generated artefacts:
     *  resolve a relative path inside the suite folder. */
    public static String resolveOutputPath(String suiteFolder, String relativeOrAbsolute) {
        if (relativeOrAbsolute == null || relativeOrAbsolute.isBlank()) return null;
        if (relativeOrAbsolute.contains("/")) return relativeOrAbsolute;
        return suiteFolder + "/" + relativeOrAbsolute;
    }

    /** Helper for tests / direct callers that already have a manifest. */
    public List<CalendarFile> listCalendars(String tenantId, String projectName, String folder) {
        return loadCalendars(tenantId, projectName, normaliseFolder(folder));
    }
}
