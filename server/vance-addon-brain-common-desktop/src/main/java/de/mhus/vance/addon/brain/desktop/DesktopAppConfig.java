package de.mhus.vance.addon.brain.desktop;

import de.mhus.vance.shared.document.kind.ApplicationDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Typed view onto the {@code config.common-desktop} block of an
 * {@link ApplicationDocument} with {@code app: common-desktop}.
 *
 * <p>The manifest only steers <em>presentation</em> — which apps are
 * shown and in what order. It never carries per-app status config;
 * that lives in each reported app's own manifest (e.g.
 * {@code config.kanban.status}).
 *
 * <pre>
 * common-desktop:
 *   root: .                     # scan root, relative to the desktop folder
 *                               # ("." or absent = own folder). A leading
 *                               # "/" makes it a project-absolute path.
 *   recurse: false              # false = only direct child apps
 *   exclude: [canvas]           # app types to hide (common-desktop is
 *                               # always excluded — no nested desktops)
 *   include: []                 # if non-empty, ONLY these app types
 *   order: [kanban, calendar]   # app types first, in this order; the
 *                               # rest follow by folder name
 * </pre>
 */
public final class DesktopAppConfig {

    public static final String APP_NAME = "common-desktop";

    private final @Nullable String root;
    private final boolean recurse;
    private final List<String> include;
    private final List<String> exclude;
    private final List<String> order;

    private DesktopAppConfig(@Nullable String root, boolean recurse,
                             List<String> include, List<String> exclude,
                             List<String> order) {
        this.root = root;
        this.recurse = recurse;
        this.include = include;
        this.exclude = exclude;
        this.order = order;
    }

    public @Nullable String root() { return root; }
    public boolean recurse() { return recurse; }
    public List<String> include() { return include; }
    public List<String> exclude() { return exclude; }
    public List<String> order() { return order; }

    public static DesktopAppConfig defaults() {
        return new DesktopAppConfig(null, false, List.of(), List.of(), List.of());
    }

    public static DesktopAppConfig from(ApplicationDocument doc) {
        if (!APP_NAME.equalsIgnoreCase(doc.app())) {
            throw new IllegalArgumentException(
                    "ApplicationDocument is app='" + doc.app()
                            + "', cannot reinterpret as common-desktop.");
        }
        Object raw = doc.config().get(APP_NAME);
        if (raw instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return from(typed);
        }
        return defaults();
    }

    public static DesktopAppConfig from(Map<String, Object> block) {
        String root = stringOrNull(block.get("root"));
        boolean recurse = asBoolean(block.get("recurse"));
        List<String> include = lowerStringList(block.get("include"));
        List<String> exclude = lowerStringList(block.get("exclude"));
        List<String> order = lowerStringList(block.get("order"));
        return new DesktopAppConfig(root, recurse, include, exclude, order);
    }

    // ── Helpers ───────────────────────────────────────────────────

    private static @Nullable String stringOrNull(@Nullable Object v) {
        if (v instanceof String s && !s.isBlank()) return s.trim();
        return null;
    }

    private static boolean asBoolean(@Nullable Object v) {
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s.trim());
        return false;
    }

    private static List<String> lowerStringList(@Nullable Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof String s && !s.isBlank()) {
                out.add(s.trim().toLowerCase(Locale.ROOT));
            } else if (o != null) {
                out.add(o.toString().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }
}
