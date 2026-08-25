package de.mhus.vance.addon.brain.bistromath;

import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import de.mhus.vance.toolpack.ToolException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Works out what an app loads, from three places that all say it differently.
 *
 * <p>The program is one script and the guest has no module system (§6.3): every
 * file is evaluated into the same global scope, in order. So "what loads" is a
 * single ordered list, and the whole job here is to build that list from:
 *
 * <ol>
 *   <li>{@code required:} in the manifest — the app's own dependencies,</li>
 *   <li>{@code required:} on a view's root — what that screen needs,</li>
 *   <li>{@code @require} in the header of a script — including a library's own,
 *       which is what makes the graph transitive.</li>
 * </ol>
 *
 * <p><b>A require names a library; an app-local file is found, not asked for.</b>
 * That split is the design decision, and it is why there is no
 * {@code required: ./helpers.js}. A library lives under
 * {@link #LIBRARY_PREFIX}, is versioned and is shared; a file inside the app
 * folder carrying {@code $meta.kind: app-script} belongs to this app and needs
 * no name. Making both go through requires would mean versioning files that
 * only ever have one version, and inventing a path syntax the schema does not
 * otherwise have.
 *
 * <p><b>Highest version wins, and says so.</b> Two versions of one library
 * cannot coexist in a single global scope — there is no second scope to put one
 * in — so the choice is between refusing to run and picking one. Picking the
 * highest is the useful guess: a v2 is far more likely to still serve a v1
 * caller than the other way round. It is a *guess* though, and the warning is
 * the whole mitigation: it names both versions and who asked for what, so the
 * author can pin the library or fix the caller.
 */
@Service
@Slf4j
public class RequireResolver {

    /** Where a library lives. Resolved through the document cascade. */
    static final String LIBRARY_PREFIX = "_vance/app-libs/";

    /**
     * A {@code @require} in a script's <b>header</b>: the comment block before
     * the first line of code.
     *
     * <p>Header-only on purpose. Scanning the whole file would pick up a
     * {@code @require} inside a doc comment halfway down — mentioned, not meant
     * — and an author cannot see the difference from the outside. A rule that
     * says "the top of the file" is one a reader can check.
     */
    private static final Pattern REQUIRE_LINE = Pattern.compile(
            // Leading comment marker (any of //, /*, *), the directive, the
            // name — and an optional closing */ so a one-line block comment
            // works. Without that tail `/* @require db@1 */` matched nothing,
            // which is the spelling half of these will be written in.
            "^\\s*(?://+|/?\\*+)?\\s*@require\\s+(\\S+)\\s*(?:\\*/)?\\s*$");

    /** A line that ends the header: the first thing that is neither blank nor a comment. */
    private static final Pattern HEADER_LINE =
            Pattern.compile("^\\s*(//.*|/\\*.*|\\*.*|\\*/\\s*)?$");

    /** The {@code @app-script} marker, same header rules as a require. */
    private static final Pattern MARKER_LINE = Pattern.compile(
            "^\\s*(?://+|/?\\*+)?\\s*@" + BistromathConfig.SCRIPT_MARKER
                    + "\\s*(?:\\*/)?\\s*$");

    /** Bound on the walk, so a pathological graph cannot spin. */
    private static final int MAX_LIBRARIES = 64;

    /**
     * Bound on how often the walk is repeated after it discovered new wants.
     *
     * <p>Each pass can only add wants, and a version once asked for is never
     * withdrawn, so the graph settles — usually after two passes, one to find
     * the transitive requires and one to decide with them in hand. The cap is
     * the same kind of insurance as {@link #MAX_LIBRARIES}: it makes a
     * pathological graph slow rather than endless.
     */
    private static final int MAX_RESOLVE_PASSES = 8;

    private final DocumentService documentService;

    public RequireResolver(DocumentService documentService) {
        this.documentService = documentService;
    }

    /**
     * @param folder  the app folder.
     * @param views   the views found in it — each is read for its own requires.
     * @param program the program document path, or {@code null}.
     */
    public RequireReport resolve(String tenantId, String projectId, String folder,
                                BistromathConfig config, List<ViewRef> views,
                                @Nullable String program) {
        Collector collector = new Collector(tenantId, projectId);

        for (String raw : config.required()) {
            collector.want(raw, folder + "/" + BistromathConfig.MANIFEST_NAME);
        }
        for (ViewRef view : views) {
            for (String raw : viewRequires(tenantId, projectId, view)) {
                collector.want(raw, view.path());
            }
        }

        // App-local scripts first in the *discovery*, but they land after the
        // libraries in the load order below: a helper may use a library, and
        // nothing in a library can use a helper it has never heard of.
        List<LoadedScript> appScripts = appScripts(tenantId, projectId, folder, program);
        for (LoadedScript script : appScripts) {
            for (String raw : headerRequires(readOrEmpty(tenantId, projectId, script.path()))) {
                collector.want(raw, script.path());
            }
        }
        if (program != null) {
            for (String raw : headerRequires(readOrEmpty(tenantId, projectId, program))) {
                collector.want(raw, program);
            }
        }

        List<LoadedScript> out = new ArrayList<>(collector.resolveLibraries());
        out.addAll(appScripts);
        if (program != null) {
            out.add(new LoadedScript(program, LoadedScript.PROGRAM, null, null,
                    "project", null));
        }
        return new RequireReport(List.copyOf(out), List.copyOf(collector.warnings),
                List.copyOf(collector.missing));
    }

    /**
     * The source of one document in the load list, through the cascade.
     *
     * <p>Needed because a **bundled** library is not a document: it is a
     * classpath resource, so the generic document API cannot serve it. The
     * client therefore cannot read the load list by itself, and the alternative
     * — mirroring bundled libraries into documents at boot — would freeze them
     * at the version of whichever build ran first.
     */
    public String read(String tenantId, String projectId, String path) {
        return documentService.lookupCascade(tenantId, projectId, path)
                .map(LookupResult::content)
                .orElseThrow(() -> new ToolException("No document or bundled resource at '"
                        + path + "'."));
    }

    // ── sources ──────────────────────────────────────────────────────

    /**
     * A view's own requires, from a top-level {@code required:} list.
     *
     * <p>Read from the raw YAML rather than from the parsed {@link ViewNode},
     * because a view that does not parse still has requires — and the analysis
     * is most useful exactly then. A broken view is reported by the parser; it
     * must not also swallow the list.
     */
    private List<String> viewRequires(String tenantId, String projectId, ViewRef view) {
        Object root = BistromathYaml.load(readOrEmpty(tenantId, projectId, view.path()),
                view.path());
        if (!(root instanceof Map<?, ?> map)) return List.of();
        return strings(map.get("required"));
    }

    /**
     * App-local scripts: a document in the folder whose header says it is one.
     *
     * <p>Ordered by path, so the load order is a property of the folder and not
     * of whatever order a listing happened to come back in.
     *
     * <p>Marked in the header rather than by {@code $meta.kind}, because a
     * {@code .js} document cannot carry a kind — see
     * {@link BistromathConfig#SCRIPT_MARKER}.
     */
    private List<LoadedScript> appScripts(String tenantId, String projectId, String folder,
                                          @Nullable String program) {
        List<LoadedScript> out = new ArrayList<>();
        for (String path : new TreeMap<>(documentService
                .listByPrefixCascade(tenantId, projectId, folder)).keySet()) {
            if (path.equals(program) || !path.endsWith(".js")) continue;
            if (hasMarker(readOrEmpty(tenantId, projectId, path))) {
                out.add(new LoadedScript(path, LoadedScript.APP_SCRIPT, null, null,
                        "project", null));
            }
        }
        return out;
    }

    /** Whether a script's header declares it part of the app. */
    static boolean hasMarker(String source) {
        for (String line : source.split("\r?\n", -1)) {
            if (MARKER_LINE.matcher(line).matches()) return true;
            if (!HEADER_LINE.matcher(line).matches()) return false;
        }
        return false;
    }

    /**
     * The {@code @require} lines in a script's header.
     *
     * <p>Stops at the first line that is neither blank nor a comment: from there
     * on it is code, and a require below it would be a lie about when it loads.
     */
    static List<String> headerRequires(String source) {
        List<String> out = new ArrayList<>();
        for (String line : source.split("\r?\n", -1)) {
            Matcher m = REQUIRE_LINE.matcher(line);
            if (m.matches()) {
                out.add(m.group(1));
                continue;
            }
            if (!HEADER_LINE.matcher(line).matches()) break;
        }
        return out;
    }

    private String readOrEmpty(String tenantId, String projectId, String path) {
        return documentService.findByPath(tenantId, projectId, path)
                .map(documentService::readContent)
                .orElse("");
    }

    private static List<String> strings(@Nullable Object raw) {
        if (raw == null) return List.of();
        if (raw instanceof String s) return List.of(s);
        if (!(raw instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            if (o != null) out.add(String.valueOf(o));
        }
        return out;
    }

    // ── resolution ───────────────────────────────────────────────────

    /** Accumulates wants, then walks them into an ordered library list. */
    private final class Collector {
        private final String tenantId;
        private final String projectId;
        /** name → every version anybody asked for, with who asked. */
        private final Map<String, Map<String, Set<String>>> wanted = new LinkedHashMap<>();
        private final List<String> warnings = new ArrayList<>();
        private final List<String> missing = new ArrayList<>();

        Collector(String tenantId, String projectId) {
            this.tenantId = tenantId;
            this.projectId = projectId;
        }

        void want(String raw, String askedBy) {
            RequireSpec spec;
            try {
                spec = RequireSpec.parse(raw, askedBy);
            } catch (ToolException e) {
                // A misspelled require is reported, not thrown: one bad line in
                // one document must not take down the analysis of the rest,
                // which is what the reader opened this for.
                missing.add(e.getMessage());
                return;
            }
            wanted.computeIfAbsent(spec.name(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(spec.version(), k -> new LinkedHashSet<>())
                    .add(askedBy);
        }

        /**
         * Walk the wants depth-first and emit in post-order, so a library lands
         * after everything it needs.
         *
         * <p><b>Repeated until the want graph stops growing.</b> A want is not
         * a static input here: a library's own header is only read once it has
         * been located, which needs a version, which is decided from the wants
         * known <i>at that moment</i>. So a transitive {@code @require db@2}
         * inside {@code foo@1} arrives strictly after {@code db} was already
         * picked and emitted at v1 — and {@link #visit} returns early on
         * anything emitted, so the higher version was never loaded and
         * {@link #pickVersion} never saw two versions to warn about. That is
         * the silent lower-version resolution this loop exists to prevent, in
         * exactly the case "highest version wins, and says so" was written for.
         *
         * <p>Each pass starts from an empty {@code emitted} but keeps the
         * accumulated {@code wanted}, which only ever grows; the narration of a
         * discarded pass is rolled back, because it was reasoned out on an
         * incomplete graph and would otherwise be reported twice.
         */
        List<LoadedScript> resolveLibraries() {
            int warningMark = warnings.size();
            int missingMark = missing.size();
            for (int pass = 1; ; pass++) {
                int before = wantSize();
                Map<String, LoadedScript> emitted = new LinkedHashMap<>();
                for (String name : List.copyOf(wanted.keySet())) {
                    visit(name, emitted, new LinkedHashSet<>());
                }
                if (wantSize() == before) {
                    return List.copyOf(emitted.values());
                }
                if (pass >= MAX_RESOLVE_PASSES) {
                    warnings.add("The require graph was still growing after " + MAX_RESOLVE_PASSES
                            + " passes; loading what the last one worked out. Some library may"
                            + " be loaded at a lower version than something asked for.");
                    return List.copyOf(emitted.values());
                }
                warnings.subList(warningMark, warnings.size()).clear();
                missing.subList(missingMark, missing.size()).clear();
            }
        }

        /**
         * How much the want graph holds — every {@code (name, version, asker)}
         * triple. Askers count too: a second caller for a version already known
         * changes no decision but does change the {@code asked for by} line a
         * conflict warning is read for, and that line is only right once every
         * asker is in.
         */
        private int wantSize() {
            int n = 0;
            for (Map<String, Set<String>> versions : wanted.values()) {
                for (Set<String> askers : versions.values()) n += askers.size();
            }
            return n;
        }

        private void visit(String name, Map<String, LoadedScript> emitted, Set<String> onStack) {
            if (emitted.containsKey(name)) return;
            if (!onStack.add(name)) {
                warnings.add("Cycle in requires at `" + name + "` (" + String.join(" → ", onStack)
                        + "). Loading it once, in the order reached.");
                return;
            }
            try {
                if (emitted.size() >= MAX_LIBRARIES) {
                    warnings.add("More than " + MAX_LIBRARIES + " libraries; stopped at `"
                            + name + "`.");
                    return;
                }
                String version = pickVersion(name);
                String path = LIBRARY_PREFIX + name + "@" + version + ".js";
                Optional<LookupResult> hit =
                        documentService.lookupCascade(tenantId, projectId, path);
                if (hit.isEmpty()) {
                    missing.add("`" + name + "@" + version + "` was not found. Expected a document"
                            + " at `" + path + "` (this project, the tenant, or bundled)."
                            + " Asked for by: " + askers(name) + ".");
                    return;
                }
                for (String raw : headerRequires(hit.get().content())) {
                    want(raw, path);
                    RequireSpec dep = tryParse(raw);
                    if (dep != null) visit(dep.name(), emitted, onStack);
                }
                emitted.put(name, new LoadedScript(path, LoadedScript.LIBRARY, name, version,
                        origin(hit.get()), askers(name)));
            } finally {
                onStack.remove(name);
            }
        }

        /** Highest version wins; anything else gets named in a warning. */
        private String pickVersion(String name) {
            Map<String, Set<String>> versions = wanted.get(name);
            String best = null;
            for (String v : versions.keySet()) {
                if (best == null || RequireSpec.compareVersions(v, best) > 0) best = v;
            }
            if (versions.size() > 1) {
                List<String> detail = new ArrayList<>();
                for (Map.Entry<String, Set<String>> e : versions.entrySet()) {
                    detail.add(name + "@" + e.getKey() + " ← " + String.join(", ", e.getValue()));
                }
                warnings.add("`" + name + "` is required in " + versions.size()
                        + " versions; loading " + name + "@" + best + ". " + String.join("; ", detail)
                        + ". Two versions cannot both load — pin the library or fix the caller.");
            }
            return best;
        }

        private String askers(String name) {
            Set<String> all = new LinkedHashSet<>();
            for (Set<String> who : wanted.get(name).values()) all.addAll(who);
            return String.join(", ", all);
        }

        private @Nullable RequireSpec tryParse(String raw) {
            try {
                return RequireSpec.parse(raw, "");
            } catch (ToolException e) {
                return null;
            }
        }
    }

    private static String origin(LookupResult hit) {
        return switch (hit.source()) {
            case PROJECT -> "project";
            case VANCE -> "tenant";
            default -> "bundled";
        };
    }
}
