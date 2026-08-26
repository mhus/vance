package de.mhus.vance.addon.brain.bistromath;

import de.mhus.vance.toolpack.ToolException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * {@code _vance/config/applications.yaml} — the tenant's rule for custom
 * applications, parsed.
 *
 * <p><b>One document, and only from {@code _tenant}.</b> Same shape as
 * {@code _vance/config/kit-sources.yaml}. There is deliberately **no**
 * {@code applications.yaml} in a project and **no** per-app rule in an
 * {@code _app.yaml}: the party this policy addresses is somebody with project
 * WRITE, and whoever may write their own law is not addressed by it. One place,
 * one author, one file to read.
 *
 * <p>Because that one author is a tenant admin, an inner entry may <b>open</b>
 * as well as close — "everything forbidden except this app" and "all apps in
 * that project, the developers sit there" are the load-bearing cases, and both
 * are admin decisions.
 *
 * <pre>
 * default: forbidden           # global; missing file behaves like this too
 * projects:
 *   playground: allowed
 *   customer-a:
 *     mode: restricted
 *     rest: [documents, inbox]
 * apps:
 *   playground/apps/experimental/: forbidden   # prefix, longest match wins
 *   customer-a/apps/invoices:
 *     mode: restricted
 *     rest: [documents, light-llm]
 * </pre>
 *
 * <p>An app key is {@code <project>/<path-prefix>}. Matching is by
 * <b>prefix, longest wins</b> — the same rule {@code kit-sources.yaml} uses for
 * URLs — so a whole subtree is expressible and an exact path is simply the
 * longest possible prefix. The price is worth naming: renaming a folder
 * silently changes which rule applies. That is inherent to identifying an app
 * by where it lives.
 */
public record ApplicationsConfig(
        AppPolicy globalDefault,
        Map<String, AppPolicy> byProject,
        Map<String, AppPolicy> byAppPrefix,
        /**
         * Who decides a release request — a user id, or {@code null}.
         *
         * <p>Configured rather than derived: "the admin" is not an address, and
         * asking the permission provider for its ADMINs would bind this addon to
         * one provider's storage (Simple-Auth is one, the EE governor another).
         *
         * <p>{@code null} means the request path is **not offered**. A button
         * that sends into the void is worse than no button, and the refusal can
         * say so.
         */
        @Nullable String requestsTo) {

    /** The document path, in the tenant project. */
    public static final String PATH = "_vance/config/applications.yaml";

    /**
     * What applies when the document is absent.
     *
     * <p>{@code forbidden}: a powerful feature is opt-in. A tenant that wants
     * custom applications says so in one line, and the absence of the file is
     * not a quiet yes.
     */
    public static ApplicationsConfig missing() {
        return new ApplicationsConfig(AppPolicy.forbidden(), Map.of(), Map.of(), null);
    }

    public ApplicationsConfig {
        byProject = Map.copyOf(byProject);
        byAppPrefix = Map.copyOf(byAppPrefix);
    }

    /**
     * The rule for one app: app prefix, then project, then global.
     *
     * <p>First match wins — this is a **lookup**, not a merge. Two rules that
     * both apply would otherwise have to be combined, and "restricted plus
     * allowed" has no meaning that an admin could predict from reading the file.
     */
    public AppPolicy resolve(String projectId, String appFolder) {
        AppPolicy explicit = explicitAppRule(projectId, appFolder);
        return explicit != null ? explicit : projectOrGlobal(projectId);
    }

    /**
     * The rule this file states **for this app**, or {@code null} when it says
     * nothing about it.
     *
     * <p>Separate from {@link #resolve} because a granted release fills exactly
     * this gap: the hand-written file wins wherever it names the app, and a
     * grant only applies where it does not. That is what keeps revocation
     * obvious — an admin names the app {@code forbidden} here instead of hunting
     * for the entry that was once approved.
     */
    public @Nullable AppPolicy explicitAppRule(String projectId, String appFolder) {
        return longestPrefixMatch(projectId + "/" + trimSlashes(appFolder));
    }

    /** The rule for the project, or the global one. */
    public AppPolicy projectOrGlobal(String projectId) {
        AppPolicy project = byProject.get(projectId);
        return project != null ? project : globalDefault;
    }

    /** The app key this file and the grant store agree on. */
    public static String appKey(String projectId, String appFolder) {
        return projectId + "/" + trimSlashes(appFolder);
    }

    private @Nullable AppPolicy longestPrefixMatch(String key) {
        AppPolicy best = null;
        int bestLength = -1;
        for (Map.Entry<String, AppPolicy> e : byAppPrefix.entrySet()) {
            String prefix = e.getKey();
            if (!key.startsWith(prefix)) continue;
            if (prefix.length() > bestLength) {
                best = e.getValue();
                bestLength = prefix.length();
            }
        }
        return best;
    }

    // ── parsing ────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public static ApplicationsConfig parse(@Nullable Object raw) {
        if (raw == null) return missing();
        if (!(raw instanceof Map<?, ?> map)) {
            throw new ToolException(PATH + ": expected a mapping at the top level.");
        }
        AppPolicy global = map.containsKey("default")
                ? policy(map.get("default"), "default")
                : AppPolicy.forbidden();

        Map<String, AppPolicy> projects = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : section(map.get("projects"), "projects").entrySet()) {
            projects.put(e.getKey(), policy(e.getValue(), "projects." + e.getKey()));
        }
        Map<String, AppPolicy> apps = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : section(map.get("apps"), "apps").entrySet()) {
            // Normalised on the way in, so a trailing slash in the file and one
            // in the lookup key cannot disagree about whether they match.
            apps.put(trimSlashes(e.getKey()), policy(e.getValue(), "apps." + e.getKey()));
        }
        String requestsTo = null;
        if (map.get("requests") instanceof Map<?, ?> requests) {
            requestsTo = str(requests.get("to"));
        } else if (map.get("requests") != null) {
            throw new ToolException(PATH + ": `requests` is not a mapping.");
        }
        return new ApplicationsConfig(global, projects, apps, requestsTo);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(@Nullable Object raw, String where) {
        if (raw == null) return Map.of();
        if (!(raw instanceof Map<?, ?> map)) {
            throw new ToolException(PATH + ": `" + where + "` is not a mapping.");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String key = e.getKey() == null ? "" : String.valueOf(e.getKey()).trim();
            if (!key.isEmpty()) out.put(key, e.getValue());
        }
        return out;
    }

    /** Either a bare mode (`allowed`) or a mapping (`{mode, rest}`). */
    private static AppPolicy policy(@Nullable Object raw, String where) {
        if (raw instanceof String s) {
            // Through the same constructor as the mapping form: a bare
            // `restricted` said `null` families here and an empty list there,
            // so the identical policy meant "unrestricted REST" or "no REST"
            // depending on how it was typed. Found by the test below.
            return of(mode(s, where), null, null, null);
        }
        if (raw instanceof Map<?, ?> map) {
            Object modeValue = map.get("mode");
            if (modeValue == null) {
                throw new ToolException(PATH + ": `" + where + "` needs a `mode`"
                        + " (forbidden, restricted or allowed).");
            }
            AppMode mode = mode(String.valueOf(modeValue), where + ".mode");
            List<String> rest = families(map.get("rest"), where + ".rest");
            Boolean surface = bool(map.get("surface"), where + ".surface");
            Boolean writable = documentsMode(map.get("documents"), where + ".documents");
            if (mode != AppMode.RESTRICTED && rest != null) {
                // Not ignored: a `rest:` list under `allowed` reads as a
                // restriction that is silently not applied, which is the worst
                // way for a policy file to be wrong.
                throw new ToolException(PATH + ": `" + where + "` sets `rest` with mode "
                        + mode.name().toLowerCase(Locale.ROOT)
                        + " — a route list only applies to `restricted`.");
            }
            return of(mode, rest, surface, writable);
        }
        throw new ToolException(PATH + ": `" + where + "` is neither a mode nor a mapping.");
    }

    /**
     * The one place that decides what a missing route list means.
     *
     * <p>`restricted` with no list is **no REST at all**. Inventing a set would
     * be guessing at what the admin meant, and guessing wide is the expensive
     * direction. Any other mode carries no list.
     */
    private static AppPolicy of(AppMode mode, @Nullable List<String> rest,
                                @Nullable Boolean surface, @Nullable Boolean writable) {
        boolean restricted = mode == AppMode.RESTRICTED;
        return new AppPolicy(
                mode,
                restricted && rest == null ? List.of() : rest,
                // The two defaults differ on purpose, and the asymmetry is the
                // point: a surface is a capability an app has to ask for, its own
                // data is one it would be crippled without.
                surface != null ? surface : !restricted,
                writable != null ? writable : true);
    }

    private static @Nullable Boolean bool(@Nullable Object raw, String where) {
        if (raw == null) return null;
        if (raw instanceof Boolean b) return b;
        throw new ToolException(PATH + ": `" + where + "` is true or false, not `" + raw + "`.");
    }

    /** {@code documents: read} or {@code write} — a word, because a bare
     *  boolean here would read as "documents: false = no documents at all". */
    private static @Nullable Boolean documentsMode(@Nullable Object raw, String where) {
        if (raw == null) return null;
        String s = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if (s.equals("read")) return false;
        if (s.equals("write")) return true;
        throw new ToolException(PATH + ": `" + where + "` is `" + raw
                + "` — expected `read` or `write`.");
    }

    private static AppMode mode(String raw, String where) {
        try {
            return AppMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ToolException(PATH + ": `" + where + "` is `" + raw
                    + "` — expected forbidden, restricted or allowed.");
        }
    }

    private static @Nullable List<String> families(@Nullable Object raw, String where) {
        if (raw == null) return null;
        if (!(raw instanceof List<?> list)) {
            throw new ToolException(PATH + ": `" + where + "` is not a list.");
        }
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            String s = o == null ? "" : String.valueOf(o).trim();
            if (!s.isEmpty()) out.add(s.toLowerCase(Locale.ROOT));
        }
        return List.copyOf(out);
    }

    private static @Nullable String str(@Nullable Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private static String trimSlashes(String s) {
        String t = s.trim();
        while (t.startsWith("/")) t = t.substring(1);
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }
}
