package de.mhus.vance.shared.starred;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.kind.ApplicationCodec;
import de.mhus.vance.shared.document.kind.ApplicationDocument;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.permission.WriteActor;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * The per-user list of starred documents. Two consumers with very different
 * needs share one store:
 *
 * <ul>
 *   <li><b>Visual</b> — {@link #listDisplayed} feeds the tile row on the landing
 *       page.</li>
 *   <li><b>Technical</b> — {@link #findByType} answers "which document takes a
 *       link?" for a later "send to". This is why the list is not merely a UI
 *       preference.</li>
 * </ul>
 *
 * <h2>The control file is the index</h2>
 *
 * Everything a caller needs to act is stored: {@code kind}, {@code type},
 * {@code title}. Reads do <b>not</b> resolve entries against their target
 * documents. Resolving would put a fan-out over N documents in N projects, each
 * with a permission check, on the two hottest paths this feature has — the login
 * landing page and the render of a "send to" menu. The file is a denormalised
 * snapshot by design.
 *
 * <p>The price is staleness, and it is paid on three named repair paths rather
 * than by verifying on every read:
 *
 * <ol>
 *   <li><b>On write</b> — {@link #star} rewrites its own entry from live truth,
 *       which heals the common case by itself.</li>
 *   <li><b>On use</b> — a click or a "send to" that fails (404/403) is the moment
 *       to offer removal. The failure is visible anyway; using it costs no extra
 *       request.</li>
 *   <li><b>On request</b> — {@link #reconcile} does the N lookups as a named
 *       action.</li>
 * </ol>
 *
 * <p>A rename of the target breaks its entry. That is accepted: the Mongo id
 * would survive a move, but "id is for persistence only, never for
 * identification in application logic" is a house rule and is not traded for
 * this convenience. {@code path} stays the key; a rename heals via path 2 or 3.
 *
 * <h2>Authorization</h2>
 *
 * Reads enforce nothing per entry — there is no resolution to hang it on. What
 * stays visible is a title and a path to a document the user starred themselves
 * and could read at the time; no content, nothing foreign. Enforcement sits at
 * the target: the click runs into 403, the "send to" fails on WRITE. Same line as
 * {@code DocumentRefResolver} — resolution is pure computation, enforcement stays
 * at the call site.
 *
 * <p>{@link #star} and {@link #reconcile} are the exception, because they touch
 * the target document: both enforce {@code READ} on it. Starring something you
 * cannot read must not work.
 *
 * <p>See {@code planning/starred-documents.md}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StarredService {

    /**
     * Control-file path inside the user's hub project. {@code _vance/config/} is
     * already where per-scope configuration lives ({@code kit-sources.yaml},
     * {@code project-kits.yaml}).
     */
    public static final String DOC_PATH = "_vance/config/starred.yaml";

    private static final String DOC_TITLE = "Starred documents";
    private static final List<String> DOC_TAGS = List.of("vance", "starred");

    /** Header key an application manifest carries its app name in. */
    private static final String APP_HEADER = "app";

    private static final String APPLICATION_KIND = "application";

    private final DocumentService documentService;
    private final PermissionService permissionService;

    // ──────────────────────────── Read ────────────────────────────

    /**
     * The whole control file, leniently parsed. A missing file is an empty list,
     * not an error — nobody has starred anything yet. A broken entry is skipped
     * and logged: a mistyped file must not take down the landing page.
     */
    public StarredDocument load(String tenantId, String userLogin) {
        String project = HomeBootstrapService.hubProjectName(userLogin);
        Optional<DocumentDocument> doc = documentService.findByPath(tenantId, project, DOC_PATH);
        if (doc.isEmpty()) return StarredDocument.empty();

        String content = documentService.readContent(doc.get());
        StarredCodec.Result parsed = StarredCodec.parse(content, DOC_PATH);
        if (!parsed.findings().isEmpty()) {
            log.debug("Starred list of user='{}' tenant='{}' has {} finding(s): {}",
                    userLogin, tenantId, parsed.findings().size(), parsed.findings());
        }
        return parsed.document();
    }

    /** What the landing page shows: VISIBLE only, in file order. */
    public List<StarredItem> listDisplayed(String tenantId, String userLogin) {
        return load(tenantId, userLogin).displayed();
    }

    /**
     * Everything registered: VISIBLE and HIDDEN, in file order. The management
     * view and the agent both want this — a hidden entry is deliberately still
     * usable, it is just out of the way.
     */
    public List<StarredItem> listResolvable(String tenantId, String userLogin) {
        return load(tenantId, userLogin).resolvable();
    }

    /**
     * The technical core: the starred application of a given app type, e.g.
     * {@code "links"}.
     *
     * <p>Singular by contract — "the starred links app". With two candidates the
     * first in file order wins; {@code highlight} does not take part, so a visual
     * emphasis never becomes a target selection. Ambiguity is surfaced by the
     * kind handler, not resolved here.
     */
    public Optional<StarredItem> findByType(String tenantId, String userLogin, String type) {
        return listByType(tenantId, userLogin, type).stream().findFirst();
    }

    /** Every registered entry of an app type, for callers that offer a choice. */
    public List<StarredItem> listByType(String tenantId, String userLogin, String type) {
        return load(tenantId, userLogin).resolvable().stream()
                .filter(i -> type.equals(i.type()))
                .toList();
    }

    /** Document form rather than app capability — e.g. every starred {@code workpage}. */
    public List<StarredItem> listByKind(String tenantId, String userLogin, String kind) {
        return load(tenantId, userLogin).resolvable().stream()
                .filter(i -> kind.equals(i.kind()))
                .toList();
    }

    // ──────────────────────────── Write ────────────────────────────

    /**
     * Star {@code (project, path)}, or update an existing entry. The server-owned
     * facts ({@code kind}, {@code type}) are always taken from the live document;
     * authored fields are preserved unless the caller passes a new value.
     *
     * <p>{@code title} is only overwritten when the caller supplies one — a
     * re-star must not undo a title the user typed.
     *
     * <p>Re-starring a disabled entry switches it back on and keeps its authored
     * content; that is what makes the toggle recoverable after a mis-click.
     *
     * @throws StarredException when the target does not exist
     */
    public StarredItem star(
            String tenantId,
            String userLogin,
            String project,
            String path,
            @Nullable String title,
            @Nullable String description,
            @Nullable Boolean highlight,
            @Nullable Boolean hidden,
            SecurityContext ctx) {

        DocumentDocument target = requireReadable(tenantId, project, path, ctx);

        StarredDocument doc = load(tenantId, userLogin);
        StarredItem existing = doc.find(project, path).orElse(null);

        StarredItem.Builder b = StarredItem.builder()
                .project(project)
                .path(path)
                .kind(resolvedKind(target))
                .type(resolvedType(target))
                .enabled(true);

        if (existing != null) {
            b.title(title != null ? title : existing.title())
                    .description(description != null ? description : existing.description())
                    .highlight(highlight != null ? highlight : existing.highlight())
                    .hidden(hidden != null ? hidden : existing.hidden())
                    .extra(existing.extra());
        } else {
            b.title(title != null ? title : fallbackTitle(target))
                    .description(description)
                    .highlight(Boolean.TRUE.equals(highlight))
                    .hidden(Boolean.TRUE.equals(hidden));
        }

        StarredItem item = b.build();
        write(tenantId, userLogin, doc.upsert(item), ctx);
        return item;
    }

    /**
     * Unstar {@code (project, path)}.
     *
     * <p>Removes the entry — <b>unless</b> it carries authored content, in which
     * case it is only switched off ({@code enabled: false}). A mis-click must not
     * eat a typed description.
     *
     * @return {@code true} when something changed
     */
    public boolean unstar(
            String tenantId, String userLogin, String project, String path, SecurityContext ctx) {

        StarredDocument doc = load(tenantId, userLogin);
        StarredItem existing = doc.find(project, path).orElse(null);
        if (existing == null) return false;

        StarredDocument updated = existing.hasAuthoredContent()
                ? doc.upsert(existing.withEnabled(false))
                : doc.remove(project, path);

        write(tenantId, userLogin, updated, ctx);
        return true;
    }

    /**
     * Set an entry's visibility without changing its registration — the
     * "show on the start page" checkbox next to the star.
     *
     * @return {@code true} when the entry existed
     */
    public boolean setHidden(
            String tenantId, String userLogin, String project, String path,
            boolean hidden, SecurityContext ctx) {

        StarredDocument doc = load(tenantId, userLogin);
        StarredItem existing = doc.find(project, path).orElse(null);
        if (existing == null) return false;
        if (existing.hidden() == hidden) return true;

        write(tenantId, userLogin, doc.upsert(existing.withHidden(hidden)), ctx);
        return true;
    }

    // ─────────────────────────── Reconcile ───────────────────────────

    /** What a reconcile found for one entry. */
    public enum ReconcileOutcome {
        /** Target resolved and the stored facts already matched. */
        OK,
        /** Target resolved and the stored facts were refreshed from it. */
        REFRESHED,
        /** No document at {@code (project, path)} any more. */
        MISSING,
        /** Target exists but this user may no longer read it. */
        FORBIDDEN
    }

    public record ReconcileEntry(
            String project, String path, ReconcileOutcome outcome, String message) {}

    public record ReconcileResult(List<ReconcileEntry> entries, boolean changed) {
        public ReconcileResult {
            entries = List.copyOf(entries);
        }
    }

    /**
     * Resolve every entry against its target: refresh the server-owned facts
     * where they drifted, and report what could not be resolved.
     *
     * <p>Deliberately does <b>not</b> delete anything. A missing target may be a
     * transient pod or permission problem, and the entry is still a curation the
     * user made; removing it is their call. The report is what the UI turns into
     * a "remove" offer.
     */
    public ReconcileResult reconcile(String tenantId, String userLogin, SecurityContext ctx) {
        StarredDocument doc = load(tenantId, userLogin);
        List<ReconcileEntry> entries = new ArrayList<>(doc.items().size());
        List<StarredItem> updated = new ArrayList<>(doc.items().size());
        boolean changed = false;

        for (StarredItem item : doc.items()) {
            Optional<DocumentDocument> found =
                    documentService.findByPath(tenantId, item.project(), item.path());
            if (found.isEmpty()) {
                entries.add(new ReconcileEntry(item.project(), item.path(),
                        ReconcileOutcome.MISSING, "no document at this path any more"));
                updated.add(item);
                continue;
            }
            if (!permissionService.check(
                    ctx, new Resource.Document(tenantId, item.project(), item.path()),
                    Action.READ)) {
                entries.add(new ReconcileEntry(item.project(), item.path(),
                        ReconcileOutcome.FORBIDDEN, "you may no longer read this document"));
                updated.add(item);
                continue;
            }

            DocumentDocument target = found.get();
            String kind = resolvedKind(target);
            String type = resolvedType(target);
            boolean drifted = !kind.equals(item.kind())
                    || !java.util.Objects.equals(type, item.type());

            if (drifted) {
                changed = true;
                updated.add(item.withResolved(kind, type, item.title()));
                entries.add(new ReconcileEntry(item.project(), item.path(),
                        ReconcileOutcome.REFRESHED,
                        "kind/type refreshed to '" + kind
                                + (type == null ? "" : "' / '" + type) + "'"));
            } else {
                updated.add(item);
                entries.add(new ReconcileEntry(item.project(), item.path(),
                        ReconcileOutcome.OK, "up to date"));
            }
        }

        if (changed) {
            write(tenantId, userLogin, doc.withItems(updated), ctx);
        }
        return new ReconcileResult(entries, changed);
    }

    // ──────────────────────────── Internals ────────────────────────────

    /**
     * Persist the control file. The hub project is a {@code SYSTEM}-kind project
     * owned by the user, so the write is a trusted server write on their behalf —
     * {@link WriteActor#system} keeps the real user for the audit trail. Same
     * shape as {@code UserMemoryService}, which writes persona/facts into the
     * same project.
     */
    private void write(
            String tenantId, String userLogin, StarredDocument doc, SecurityContext ctx) {
        String project = HomeBootstrapService.hubProjectName(userLogin);
        documentService.upsertText(
                tenantId, project, DOC_PATH, DOC_TITLE, DOC_TAGS,
                StarredCodec.serialize(doc), userLogin, WriteActor.system(ctx));
    }

    private DocumentDocument requireReadable(
            String tenantId, String project, String path, SecurityContext ctx) {
        permissionService.enforce(
                ctx, new Resource.Document(tenantId, project, path), Action.READ);
        return documentService.findByPath(tenantId, project, path)
                .orElseThrow(() -> new StarredException(
                        "No document '" + path + "' in project '" + project + "'"));
    }

    /**
     * The document's kind, or the {@code text} fallback. Most plain Markdown
     * documents carry no header and therefore no kind; the field is non-null on
     * purpose so no caller has to special-case its absence.
     */
    private static String resolvedKind(DocumentDocument doc) {
        String kind = doc.getKind();
        return (kind == null || kind.isBlank()) ? StarredItem.DEFAULT_KIND : kind;
    }

    /**
     * The app type of an application manifest, {@code null} for everything else.
     * Read from the header projection rather than by parsing the body — the
     * {@code app} key is a scalar meta key, so {@code DocumentDocument.headers}
     * already carries it.
     */
    private static @Nullable String resolvedType(DocumentDocument doc) {
        if (!APPLICATION_KIND.equals(doc.getKind())) return null;
        String app = doc.getHeaders().get(APP_HEADER);
        return (app == null || app.isBlank()) ? null : app;
    }

    /**
     * Label for a first star, when the caller supplied none.
     *
     * <p>The file stem alone is wrong for an application: every manifest is
     * called {@code _app.yaml}, so every app would end up with the same tile
     * label. For those, the manifest's own {@code title} comes first (that is
     * what the app calls itself), then the containing folder — which is how a
     * folder-derived app is identified everywhere else in Vance.
     */
    private @Nullable String fallbackTitle(DocumentDocument doc) {
        String title = doc.getTitle();
        if (title != null && !title.isBlank()) return title;

        String path = doc.getPath();
        if (APPLICATION_KIND.equals(doc.getKind())) {
            String manifestTitle = manifestTitle(doc);
            if (manifestTitle != null) return manifestTitle;
            String folder = parentFolderName(path);
            if (folder != null) return folder;
        }
        String stem = fileStem(path);
        return stem.isBlank() ? null : stem;
    }

    /**
     * The {@code title} an application manifest gives itself, or {@code null}
     * when it has none or cannot be read. One body read, only on the star of an
     * application — the label is the whole point of the field, so it is worth a
     * request that a re-star does not repeat.
     */
    private @Nullable String manifestTitle(DocumentDocument doc) {
        try {
            ApplicationDocument manifest = ApplicationCodec.parse(
                    documentService.readContent(doc), doc.getMimeType());
            String title = manifest.title();
            return (title == null || title.isBlank()) ? null : title;
        } catch (RuntimeException e) {
            log.debug("Could not read manifest title of '{}': {}", doc.getPath(), e.toString());
            return null;
        }
    }

    private static @Nullable String parentFolderName(String path) {
        int last = path.lastIndexOf('/');
        if (last <= 0) return null;
        int prev = path.lastIndexOf('/', last - 1);
        String folder = path.substring(prev + 1, last);
        return folder.isBlank() ? null : folder;
    }

    private static String fileStem(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /** A star operation that cannot be carried out — surfaced as 404 by the REST layer. */
    public static class StarredException extends RuntimeException {
        public StarredException(String message) {
            super(message);
        }
    }
}
