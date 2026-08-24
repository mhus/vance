package de.mhus.vance.addon.brain.bistromath;

import de.mhus.vance.brain.applications.VanceApplication;
import de.mhus.vance.brain.prompt.ForeignPromptText;
import de.mhus.vance.brain.tools.document.DocumentLinkBuilder;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.toolpack.ToolException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * {@link VanceApplication} for {@code app: custom} — an application whose
 * behaviour lives in documents.
 *
 * <p>Every other app implementation in the tree pairs this bean with a
 * compiled Vue component: the Java side owns the manifest, the TypeScript side
 * owns the interaction. Bistromath is the one where the interaction is also
 * documents — a view document holds a widget tree, a program document holds
 * the behaviour. The renderer is generic; what makes one app differ from
 * another is entirely on disk.
 *
 * <p>Two consequences shape this class:
 *
 * <ul>
 *   <li>{@link #refresh} is the app's <b>validator</b>. Nothing else checks a
 *       view: {@code kind_validate} does not reach app documents yet, and a
 *       widget tree that fails to parse is otherwise discovered by opening the
 *       app.</li>
 *   <li>Nothing is read from a manifest registry. Views are <b>found</b> by
 *       their own {@code $meta.kind}, the program by convention. So refresh is
 *       also the only place that reports <em>what the runtime sees</em>, which
 *       is what makes a convention-based layout answerable instead of tacit.</li>
 * </ul>
 */
@Service
@Slf4j
public class BistromathApplication implements VanceApplication {

    public static final String APP_NAME = BistromathConfig.BLOCK;

    private static final String MD_MIME = "text/markdown";
    private static final String INDEX = "_index.md";
    private static final String STARTER_VIEW = "main.yaml";
    private static final int STATUS_ITEM_LIMIT = 8;

    private final BistromathStore store;
    private final DocumentLinkBuilder linkBuilder;

    public BistromathApplication(BistromathStore store, DocumentLinkBuilder linkBuilder) {
        this.store = store;
        this.linkBuilder = linkBuilder;
    }

    @Override
    public String appName() {
        return APP_NAME;
    }

    /**
     * Scaffold a new app: a manifest, one view, one program — a Hello World
     * that runs.
     *
     * <p>The scaffold is deliberately <b>not</b> a note about itself. The first
     * build wrote a view whose markdown explained which widgets exist; opening
     * it taught the reader nothing that the manual does not, and it did
     * nothing. A button that answers with the date proves the whole chain —
     * program loaded, handler resolved, host API reached, state rendered — in
     * one click.
     *
     * <p>Three files rather than one, and that is not a violation of the
     * simplicity budget: the budget counts what an author must <em>declare</em>,
     * not what the scaffold writes. Nobody types these.
     */
    @Override
    public CreateResult create(CreateContext ctx) {
        String folder = BistromathStore.normaliseFolder(ctx.folder());
        Map<String, Object> params = ctx.params() == null ? Map.of() : ctx.params();

        String manifestPath = BistromathStore.manifestPath(folder);
        String viewPath = folder + "/" + STARTER_VIEW;
        String programPath = folder + "/" + BistromathConfig.DEFAULT_PROGRAM;

        if (!ctx.overwrite()) {
            requireAbsent(ctx, manifestPath, "manifest");
            // The view and the program are guarded separately: a document can
            // outlive its manifest, and writing over one goes through an update
            // that would silently replace work.
            requireAbsent(ctx, viewPath, "view");
            requireAbsent(ctx, programPath, "program");
        }

        String title = asString(params.get("title"));
        String description = asString(params.get("description"));
        String label = title == null ? leafFolderName(folder) : title;

        // No `landing`: with one view it is the first one anyway, and a key that
        // states the only possibility is noise.
        DocumentDocument manifest = store.writeManifest(ctx.tenantId(), ctx.projectName(),
                folder, title, description, BistromathConfig.empty(), ctx.userId());

        store.writeDocument(ctx.tenantId(), ctx.projectName(), viewPath, label,
                BistromathStore.YAML_MIME, starterView(label),
                List.of(BistromathConfig.VIEW_KIND), ctx.userId());

        store.writeDocument(ctx.tenantId(), ctx.projectName(), programPath, label + " — program",
                BistromathStore.JS_MIME, starterProgram(),
                List.of(APP_NAME, "program"), ctx.userId());

        RefreshResult refresh = refresh(new RefreshContext(ctx.tenantId(), ctx.projectName(),
                folder, ctx.userId(), ctx.processId()));

        log.info("BistromathApplication.create tenant='{}' folder='{}'",
                ctx.tenantId(), folder);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("viewCount", 1);

        return new CreateResult(APP_NAME, folder, manifest.getPath(),
                linkBuilder.linkFor(manifest, ctx.projectName()),
                List.of(), refresh.artefacts(),
                "Open the app and press Hello. Shape the page in `" + viewPath
                        + "`, the behaviour in `" + programPath + "`.",
                stats);
    }

    private void requireAbsent(CreateContext ctx, String path, String what) {
        if (store.documentExists(ctx.tenantId(), ctx.projectName(), path)) {
            throw new ToolException("A document already exists at '" + path + "' (the app's "
                    + what + "). Move or delete it, or pass overwrite=true to replace it.");
        }
    }

    /**
     * Rewrite the index and check what the runtime can see.
     *
     * <p>Problems are collected, not thrown. Throwing on the first bad view
     * would hide the other four, and an author fixing an app wants the whole
     * list — that is the difference between one round trip and four.
     */
    @Override
    public RefreshResult refresh(RefreshContext ctx) {
        String folder = BistromathStore.normaliseFolder(ctx.folder());
        BistromathStore.Loaded loaded = store.load(ctx.tenantId(), ctx.projectName(), folder);
        BistromathStore.Discovered found =
                store.discoverViews(ctx.tenantId(), ctx.projectName(), folder);

        List<String> problems = new ArrayList<>(found.problems());
        if (found.views().isEmpty()) {
            problems.add("No view found. A view is a document whose `$meta.kind` is `"
                    + BistromathConfig.VIEW_KIND + "`; without one the app opens empty.");
        }
        for (ViewRef view : found.views()) {
            try {
                store.readView(ctx.tenantId(), ctx.projectName(), view);
            } catch (RuntimeException e) {
                problems.add("View '" + view.handle() + "': " + e.getMessage());
            }
        }

        BistromathConfig config = loaded.config();
        Optional<DocumentDocument> program =
                store.findProgram(ctx.tenantId(), ctx.projectName(), folder, config);
        if (program.isEmpty() && config.init() != null) {
            problems.add("The manifest names `init: " + config.init()
                    + "`, but no document is there.");
        }
        String landing = config.landing();
        if (landing != null && found.views().stream().noneMatch(v -> v.handle().equals(landing))) {
            problems.add("`landing: " + landing + "` names no view that exists.");
        }

        String title = loaded.manifestDoc().title();
        if (title == null || title.isBlank()) title = leafFolderName(folder);

        DocumentDocument index = store.writeDocument(ctx.tenantId(), ctx.projectName(),
                folder + "/" + INDEX, "Index — " + title, MD_MIME,
                renderIndex(found.views(), config, program.map(DocumentDocument::getPath)
                        .orElse(null), title, problems),
                List.of(APP_NAME, "generated", "index"), ctx.userId());

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("viewCount", found.views().size());
        stats.put("hasProgram", program.isPresent());
        stats.put("problemCount", problems.size());
        if (!problems.isEmpty()) stats.put("problems", List.copyOf(problems));

        log.info("BistromathApplication.refresh tenant='{}' folder='{}' views={} problems={}",
                ctx.tenantId(), folder, found.views().size(), problems.size());

        return new RefreshResult(APP_NAME, folder, List.of(new ArtefactResult(
                "index", index.getPath(), linkBuilder.linkFor(index, ctx.projectName()),
                stats)));
    }

    /** An abacus: this app computes from what is written down. */
    @Override
    public AppCard describe(DescribeContext ctx) {
        return new AppCard("🧮", null);
    }

    @Override
    public Optional<AppStatus> status(StatusContext ctx) {
        BistromathStore.Discovered found;
        try {
            found = store.discoverViews(ctx.tenantId(), ctx.projectName(), ctx.folder());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        List<StatusItem> items = new ArrayList<>();
        for (ViewRef v : found.views()) {
            if (items.size() >= STATUS_ITEM_LIMIT) break;
            items.add(new StatusItem(v.title() == null ? v.handle() : v.title(),
                    v.handle(), null, null));
        }
        List<StatusMetric> metrics = new ArrayList<>();
        metrics.add(new StatusMetric("Views", Integer.toString(found.views().size())));
        if (!found.problems().isEmpty()) {
            metrics.add(new StatusMetric("Problems",
                    Integer.toString(found.problems().size())));
        }
        int views = found.views().size();
        String headline = views == 1 ? "1 view" : views + " views";
        // ATTENTION, not BLOCKED: a reported problem means one view or handle is
        // unusable, never that the app cannot open.
        return Optional.of(new AppStatus(headline,
                found.problems().isEmpty() ? StatusSeverity.OK : StatusSeverity.ATTENTION,
                metrics, items, null));
    }

    /**
     * The views, as places a link can point at.
     *
     * <p>{@code NAVIGATE} only. A view is exactly what "open the app at X"
     * means. There are no {@code INTAKE} targets: a generic app declares no
     * place where a shared thing belongs, and guessing one would be a
     * convention the author never agreed to.
     */
    @Override
    public List<AppTarget> targets(TargetsContext ctx) {
        if (ctx.purpose() != TargetPurpose.NAVIGATE) return List.of();
        BistromathStore.Discovered found;
        try {
            found = store.discoverViews(ctx.tenantId(), ctx.projectName(),
                    BistromathStore.normaliseFolder(ctx.folder()));
        } catch (RuntimeException e) {
            log.debug("BistromathApplication.targets folder='{}' unreadable: {}",
                    ctx.folder(), e.toString());
            return List.of();
        }
        List<AppTarget> out = new ArrayList<>(found.views().size());
        for (ViewRef v : found.views()) {
            out.add(new AppTarget(v.handle(), v.title() == null ? v.handle() : v.title(), null));
        }
        return List.copyOf(out);
    }

    /**
     * What the agent needs to know to work on this app.
     *
     * <p>Two things beyond the inventory, both learned the hard way. First: say
     * that the app <em>is</em> its documents, because the model's prior is that
     * an app is code it cannot touch — without this it offers to "request a
     * feature" instead of editing a view. Second: name the files. An agent that
     * knows the view path edits the view; one that only knows the app exists
     * asks the user where it lives.
     */
    @Override
    public @Nullable String promptInject(PromptInjectContext ctx) {
        String folder = BistromathStore.normaliseFolder(ctx.folder());
        BistromathStore.Loaded loaded;
        try {
            loaded = store.load(ctx.tenantId(), ctx.projectName(), folder);
        } catch (RuntimeException e) {
            return "You are in a Bistromath app at `" + folder + "` whose manifest does not"
                    + " load: " + ForeignPromptText.quoted(String.valueOf(e.getMessage()))
                    + ". The manifest is `" + BistromathStore.manifestPath(folder)
                    + "` — read and repair it with the document tools.\n";
        }
        BistromathStore.Discovered found =
                store.discoverViews(ctx.tenantId(), ctx.projectName(), folder);

        StringBuilder sb = new StringBuilder();
        sb.append("You are in a Bistromath app at `").append(folder).append("`.\n")
                .append("This app is DEFINED BY ITS DOCUMENTS, not by compiled code. A view is")
                .append(" a document with `$meta.kind: ").append(BistromathConfig.VIEW_KIND)
                .append("` holding a widget tree; the behaviour is JavaScript in `")
                .append(folder).append('/').append(loaded.config().program())
                .append("`, which runs in a sandbox and reaches the server only through the")
                .append(" `vance.*` API. You can change what this app does with the ordinary")
                .append(" document tools — never say a change needs a new release.\n");

        if (found.views().isEmpty()) {
            sb.append("No view found yet, so the app opens empty.\n");
        } else {
            sb.append("Views (handle ← file name):\n");
            for (ViewRef v : found.views()) {
                sb.append("- `").append(v.handle()).append("` → `").append(v.path()).append("`\n");
            }
        }
        sb.append("A widget shows state via `from: <key>`; the program writes it with")
                .append(" `vance.state.set(key, value)` and reads documents with")
                .append(" `vance.documents.rows(path)`. There is no table declaration.\n")
                .append("Run `app_rebuild(folder=\"").append(folder).append("\")` after editing")
                .append(" — it re-reads every view and reports what does not parse.\n");
        if (!found.problems().isEmpty()) {
            sb.append("Current problems:\n");
            for (String p : found.problems()) {
                sb.append("- ").append(ForeignPromptText.quoted(p)).append('\n');
            }
        }
        return sb.toString();
    }

    // ── the scaffold ──────────────────────────────────────────────

    /**
     * The Hello World view.
     *
     * <p>Hand-built YAML rather than a serialised {@link ViewNode}: this text is
     * the author's example, so it carries the comments and the shape a person
     * would write, which a round-tripped object graph would strip.
     */
    static String starterView(String title) {
        return """
                $meta:
                  kind: %s
                # A view is a widget tree. Edit freely; `app_rebuild` re-checks it.
                type: page
                title: %s
                children:
                  - type: toolbar
                    children:
                      - type: button
                        label: Hello
                        # <program>:<function> — the separator is ':', not '#'
                        on:
                          click: "main.js:hello"
                  - type: text
                    # Shows the state key that main.js writes.
                    from: greeting
                """.formatted(BistromathConfig.VIEW_KIND, yamlScalar(title));
    }

    /**
     * The Hello World program.
     *
     * <p>Plain function declarations, not `export`: the guest source is
     * evaluated as one script, so a top-level function is reachable by name.
     * An `export` in a single-file program with no importers would be ceremony
     * for its own sake.
     */
    static String starterProgram() {
        return """
                // The program of this app. It runs in a sandbox: no DOM, no
                // cookies, no network except through `vance.*`.
                //
                // `init` runs once when the app opens, `shutdown` when it closes.
                // Everything between is events — and module state survives them,
                // because this is one long-running program, not one call per click.

                let greetings = 0;

                // `async` from the start: the moment this reads a document it
                // needs `await`, and `await` in a plain function is a syntax
                // error. Nothing waits on the view — the page is drawn first —
                // but no handler runs until init has finished.
                async function init() {
                  vance.state.set('greeting', 'Ready — press Hello.');
                }

                function hello() {
                  greetings++;
                  const now = new Date().toISOString();
                  vance.state.set('greeting', now + ' — Hello World (' + greetings + ')');
                }

                async function shutdown() {
                  // Nothing to release here — and be careful what you put here.
                  //
                  // On a normal close (tab switched, document closed) this runs
                  // and is awaited briefly. But when the whole page goes away —
                  // browser tab closed, reload, crash — an async shutdown does
                  // NOT get to finish: the browser does not wait for promises
                  // there. So never keep the only copy of something until
                  // shutdown. Write it when you have it.
                }

                // Define this one and the browser asks before the page is
                // closed or reloaded — but only while it returns true, and only
                // with the browser's own generic wording. It is re-asked after
                // every handler, so returning a plain flag is enough:
                //
                // function onBeforeUnload() {
                //   return unsavedChanges;
                // }
                //
                // It is a courtesy, not a safety net: nothing asks on a crash,
                // and the whole prompt is skipped if the reader never clicked
                // anything on the page.

                // Reading documents is asynchronous, so a handler that reads
                // has to say `async` — otherwise `await` is a syntax error:
                //
                // async function load() {
                //   const files = await vance.documents.list('records/');
                //   const rows = [];
                //   for (const f of files) {
                //     rows.push({ key: f.key, ...(await vance.documents.read(f.path)) });
                //   }
                //   vance.state.set('rows', rows);
                // }
                //
                // A path without a leading slash is relative to this app's
                // folder; '/_ext/<mount>/…' reads a mounted document, and a
                // '?a=1&b=2' on it is forwarded to the source as a
                // parameterised view.
                """;
    }

    static String renderIndex(List<ViewRef> views, BistromathConfig config,
                              @Nullable String programPath, String title,
                              List<String> problems) {
        String heading = oneLine(title);
        StringBuilder sb = new StringBuilder();
        sb.append("---\n$meta:\n  kind: workpage\n");
        sb.append("title: \"").append(escape(heading)).append(" — Index\"\n");
        sb.append("description: \"Generated: what the runtime found in this folder.\"\n");
        sb.append("---\n");
        sb.append("# ").append(heading).append("\n\n");
        sb.append("```vance-callout\nseverity: note\ntitle: Auto-generated\n")
                .append("body: This page is rewritten on every `app_rebuild` — edits here are lost.\n")
                .append("```\n\n");

        if (!problems.isEmpty()) {
            sb.append("```vance-callout\nseverity: warning\ntitle: ")
                    .append(problems.size() == 1 ? "1 problem" : problems.size() + " problems")
                    .append("\nbody: |\n");
            for (String p : problems) {
                sb.append("  - ").append(oneLine(p)).append('\n');
            }
            sb.append("```\n\n");
        }

        // The point of this section: nothing here is declared anywhere, so this
        // is the only place that answers "what does the runtime actually see".
        sb.append("## Views\n\n");
        if (views.isEmpty()) {
            sb.append("None found.\n\n");
        } else {
            for (ViewRef v : views) {
                sb.append("- `").append(v.handle()).append("` — ")
                        .append(mdText(oneLine(v.title() == null ? v.handle() : v.title())))
                        .append(" (`").append(v.path()).append("`)");
                if (v.handle().equals(config.landing())) sb.append(" — landing");
                sb.append('\n');
            }
            sb.append('\n');
        }

        sb.append("## Program\n\n");
        if (programPath == null) {
            sb.append("None — expected `").append(config.program()).append("`.\n");
        } else {
            sb.append("`").append(programPath).append("`\n");
        }
        return sb.toString();
    }

    // ── helpers ───────────────────────────────────────────────────

    /** Escape what would turn borrowed text into markdown structure. */
    private static String mdText(String s) {
        return s.replace("\\", "\\\\")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("*", "\\*")
                .replace("_", "\\_")
                .replace("<", "&lt;");
    }

    private static String oneLine(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    private static String yamlScalar(String s) {
        return "\"" + escape(oneLine(s)) + "\"";
    }

    /**
     * Quote for a double-quoted YAML scalar. Backslash first: YAML reads
     * {@code \} inside double quotes as the start of an escape, so a title like
     * {@code Docs\Apps} would produce a header the parser rejects.
     */
    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String leafFolderName(String folder) {
        int slash = folder.lastIndexOf('/');
        return slash < 0 ? folder : folder.substring(slash + 1);
    }

    private static @Nullable String asString(@Nullable Object v) {
        if (v instanceof String s && !s.isBlank()) return s.trim();
        return null;
    }
}
