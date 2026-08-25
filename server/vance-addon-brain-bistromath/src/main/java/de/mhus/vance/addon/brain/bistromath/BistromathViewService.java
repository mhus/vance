package de.mhus.vance.addon.brain.bistromath;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.toolpack.ToolException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Assembles what the client renders: the app inventory and one parsed view.
 *
 * <p>Two operations, and no data one. The app's data is read by the program
 * through the ordinary document API in the browser; this service never sees a
 * row.
 */
@Service
public class BistromathViewService {

    private final BistromathStore store;
    private final RequireResolver requireResolver;

    public BistromathViewService(BistromathStore store, RequireResolver requireResolver) {
        this.store = store;
        this.requireResolver = requireResolver;
    }

    public AppScan scan(String tenantId, String projectId, String folder) {
        BistromathStore.Loaded loaded = store.load(tenantId, projectId, folder);
        BistromathStore.Discovered found =
                store.discoverViews(tenantId, projectId, loaded.folder());
        BistromathConfig config = loaded.config();

        String title = loaded.manifestDoc().title();
        if (title == null || title.isBlank()) title = leaf(loaded.folder());

        List<String> problems = new ArrayList<>(found.problems());
        ViewRef landing = resolveLanding(found.views(), config, problems);
        Optional<DocumentDocument> program =
                store.findProgram(tenantId, projectId, loaded.folder(), config);
        if (program.isEmpty() && config.init() != null) {
            problems.add("The manifest names `init: " + config.init()
                    + "`, but no document is there.");
        }

        String programPath = program.map(DocumentDocument::getPath).orElse(null);
        RequireReport requires = requireResolver.resolve(tenantId, projectId, loaded.folder(),
                config, found.views(), programPath);
        // Warnings and misses are problems of the app, so they show wherever the
        // app's problems show. The full report stays available for the analysis
        // surface, which wants the load order too.
        problems.addAll(requires.warnings());
        problems.addAll(requires.missing());

        return new AppScan(loaded.folder(), title, loaded.manifestDoc().description(),
                found.views(), landing == null ? null : landing.handle(),
                programPath, List.copyOf(problems), requires, config.rest(), config.refresh());
    }

    /**
     * Which view opens.
     *
     * <p>A {@code landing} that names nothing is a **note**, not a failure: the
     * app still opens, on the first view found. Refusing would let one stale
     * manifest line take down an app whose views are all fine — the same late
     * binding an inter-link handle gets.
     */
    private static @Nullable ViewRef resolveLanding(List<ViewRef> views, BistromathConfig config,
                                                    List<String> problems) {
        String wanted = config.landing();
        if (wanted != null) {
            for (ViewRef v : views) {
                if (v.handle().equals(wanted)) return v;
            }
            problems.add("`landing: " + wanted + "` names no view that exists; opening the"
                    + " first one instead.");
        }
        return views.isEmpty() ? null : views.get(0);
    }

    /**
     * Parse one view.
     *
     * @param handle which view, or {@code null} for the landing one.
     */
    public RenderedView view(String tenantId, String projectId, String folder,
                            @Nullable String handle) {
        BistromathStore.Loaded loaded = store.load(tenantId, projectId, folder);
        BistromathStore.Discovered found =
                store.discoverViews(tenantId, projectId, loaded.folder());

        ViewRef ref;
        if (handle == null || handle.isBlank()) {
            ref = resolveLanding(found.views(), loaded.config(), new ArrayList<>());
            if (ref == null) {
                throw new ToolException("App '" + loaded.folder() + "' has no view. A view is a"
                        + " document with `$meta.kind: " + BistromathConfig.VIEW_KIND + "`.");
            }
        } else {
            String wanted = handle.trim();
            ref = found.views().stream().filter(v -> v.handle().equals(wanted)).findFirst()
                    .orElseThrow(() -> new ToolException("App '" + loaded.folder()
                            + "' has no view '" + wanted + "'."));
        }

        ViewNode root = store.readView(tenantId, projectId, ref);
        List<String> notes = notes(root, found.views());
        String title = ref.title() != null ? ref.title() : root.label();
        return new RenderedView(ref.handle(), title, root, notes);
    }

    /**
     * Parse one view named by its own path, with no app around it.
     *
     * <p>For a view document opened on its own in the Cortex. The handle-based
     * call above needs an app folder to resolve `landing:` and to check that a
     * `navigate:` target exists; here there is no app, so those notes cannot be
     * produced — a view is a document first and a member of an app second.
     */
    public RenderedView viewByPath(String tenantId, String projectId, String path) {
        ViewRef ref = new ViewRef(handleOf(path), path, null);
        ViewNode root = store.readView(tenantId, projectId, ref);
        return new RenderedView(ref.handle(), root.label(), root, List.of());
    }

    /** File name without its extension — the same handle rule the scan uses. */
    private static String handleOf(String path) {
        String leaf = leaf(path);
        int dot = leaf.lastIndexOf('.');
        return dot > 0 ? leaf.substring(0, dot) : leaf;
    }

    /**
     * Problems that do not stop the page.
     *
     * <p>A view document that cannot be parsed never becomes a
     * {@link RenderedView}. But a button pointing at a view that no longer
     * exists must still render — refusing the page would mean one stale handle
     * takes down an app the reader was using.
     */
    private static List<String> notes(ViewNode root, List<ViewRef> views) {
        List<String> notes = new ArrayList<>();
        walk(root, views, notes);
        return List.copyOf(notes);
    }

    private static void walk(ViewNode node, List<ViewRef> views, List<String> notes) {
        for (Map.Entry<String, ViewAction> e : node.on().entrySet()) {
            ViewAction action = e.getValue();
            if (action.kind() != ActionKind.NAVIGATE) continue;
            String target = action.target();
            if (target != null && views.stream().noneMatch(v -> v.handle().equals(target))) {
                notes.add("`" + e.getKey() + "` navigates to '" + target
                        + "', which is not a view of this app.");
            }
        }
        for (ViewNode child : node.children()) {
            walk(child, views, notes);
        }
    }

    private static String leaf(String folder) {
        int slash = folder.lastIndexOf('/');
        return slash < 0 ? folder : folder.substring(slash + 1);
    }
}
