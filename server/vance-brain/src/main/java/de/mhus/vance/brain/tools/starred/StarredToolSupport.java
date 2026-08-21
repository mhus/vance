package de.mhus.vance.brain.tools.starred;

import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.starred.StarredItem;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Shared plumbing for the {@code starred_*} tools: the user the list belongs to,
 * the security context for a write, and the row shape.
 *
 * <p>The whole family is {@code deferred()}: four permanent schemas would be
 * poorly spent tool-surface budget for a side feature, and the discovery path
 * plus the manual bring them in when they are actually wanted.
 */
@Component
@RequiredArgsConstructor
public class StarredToolSupport {

    /** Labels every tool in this family carries, plus its own read/write label. */
    static final Set<String> BASE_LABELS = Set.of("starred", "document");

    private final SecurityContextFactory contextFactory;

    /**
     * The user whose list is addressed. There is no parameter for it on purpose —
     * a tool that could name another user would be a way around the fact that the
     * store is per-person.
     *
     * <p>A headless invocation (no user bound, e.g. a scheduler-driven worker) has
     * no starred list to speak of, and guessing one would be worse than failing.
     */
    public String requireUser(ToolInvocationContext ctx) {
        String user = ctx.userId();
        if (user == null || user.isBlank()) {
            throw new ToolException(
                    "No user is bound to this process — a starred list belongs to a person. "
                            + "Run this from a user session.");
        }
        return user;
    }

    public SecurityContext subject(ToolInvocationContext ctx) {
        return contextFactory.forToolSubject(ctx.tenantId(), ctx.userId());
    }

    /**
     * Wire shape of one entry. {@code enabled} is not reported: everything the
     * tools hand out is registered by definition, so the flag would only be
     * {@code true} and cost a token per row.
     */
    public static Map<String, Object> row(StarredItem item) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("project", item.project());
        m.put("path", item.path());
        m.put("kind", item.kind());
        if (item.type() != null) m.put("type", item.type());
        if (item.title() != null) m.put("title", item.title());
        if (item.description() != null) m.put("description", item.description());
        if (item.highlight()) m.put("highlight", true);
        if (item.hidden()) m.put("hidden", true);
        return m;
    }

    public static @Nullable String paramString(Map<String, Object> params, String key) {
        Object v = params.get(key);
        if (!(v instanceof String s)) return null;
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static @Nullable Boolean paramBoolean(Map<String, Object> params, String key) {
        Object v = params.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) {
            if ("true".equalsIgnoreCase(s.trim())) return Boolean.TRUE;
            if ("false".equalsIgnoreCase(s.trim())) return Boolean.FALSE;
        }
        return null;
    }

    public static String requireParam(Map<String, Object> params, String key) {
        String v = paramString(params, key);
        if (v == null) throw new ToolException(key + " is required");
        return v;
    }
}
