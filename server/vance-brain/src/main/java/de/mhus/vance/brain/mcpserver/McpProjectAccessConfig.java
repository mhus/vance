package de.mhus.vance.brain.mcpserver;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.Yaml;

/**
 * The parsed shape of {@code _vance/config/mcp-access.yaml} — the
 * per-project access regulation for the project-scoped MCP surface
 * ({@code POST /brain/{tenant}/mcp/{project}}).
 *
 * <p>The config is a <b>ceiling on a smaller surface</b>, never a grant:
 * it can only narrow what the calling identity may already do. The
 * surface offers a fixed set of {@code doc_*} tools; the config decides
 * per caller whether they are visible at all ({@link Mode}), and to
 * which path prefixes they apply. Authorization against the account's
 * grants still happens per call — read tools enforce READ via the
 * endpoint gate and project resolution, write tools enforce per-document
 * WRITE (reserved {@code _vance/} paths need ADMIN, rule R4).
 *
 * <p>Fail-closed everywhere: a missing config, a malformed config, a
 * caller no entry matches — all mean <b>deny</b>. The MCP client sees a
 * normal protocol response with an empty catalogue or an {@code
 * isError:true} call result, never a 5xx.
 *
 * <p>Entry matching is first-match-wins in document order — the same
 * cascade reading the rest of the configuration tree uses. Who an entry
 * applies to is a {@link McpCallerFilter}: {@code token} (one integration
 * token), {@code oauth} (OAuth grants only / everyone but them) and
 * {@code users} (named accounts), all of which must match. An entry
 * with none of them applies to any authenticated caller — humans, integration
 * tokens and OAuth grants alike. Admins put specific entries first.
 */
public record McpProjectAccessConfig(List<Entry> access) {

    /** Deny-everything — the state of a project without a config. */
    public static final McpProjectAccessConfig EMPTY = new McpProjectAccessConfig(List.of());

    public McpProjectAccessConfig {
        access = access == null ? List.of() : List.copyOf(access);
    }

    /** Access mode: {@code ro} = read tools only, {@code rw} = read + write tools. */
    public enum Mode {
        RO,
        RW;

        /** Parses the YAML wire form ({@code ro}/{@code rw}), fail-closed. */
        static Mode fromWire(String wire) {
            return switch (wire.trim().toLowerCase(Locale.ROOT)) {
                case "ro" -> RO;
                case "rw" -> RW;
                default -> throw new IllegalStateException("mode must be 'ro' or 'rw', got '" + wire + "'");
            };
        }
    }

    /**
     * One access rule.
     *
     * @param name   required, human-facing — names the rule in logs and errors
     * @param caller who the rule applies to; {@link McpCallerFilter#ANY} = any authenticated caller
     * @param mode   {@code ro} or {@code rw}
     * @param paths  normalized path prefixes; empty = the whole project
     *               (writes into {@code _vance/} stay denied regardless)
     */
    public record Entry(String name, McpCallerFilter caller, Mode mode, List<String> paths) {

        public Entry {
            if (name == null || name.isBlank()) {
                throw new IllegalStateException("access entry needs a non-blank 'name'");
            }
            if (mode == null) {
                throw new IllegalStateException("access entry '" + name + "' needs a 'mode'");
            }
            if (caller == null) caller = McpCallerFilter.ANY;
            if (paths == null) paths = List.of();
            paths = normalizePrefixes(paths);
        }

        /** The integration-token id this entry is restricted to, or {@code null}. */
        public @Nullable String token() {
            return caller.token();
        }

        /** Whether this entry applies to {@code who}. */
        public boolean matches(McpCaller who) {
            return caller.matches(who);
        }

        /** No prefixes configured — the whole project is in scope. */
        public boolean isWholeProject() {
            return paths.isEmpty();
        }

        /**
         * Whether a document path (no leading slash, Vance convention)
         * lies inside the configured prefixes. Whole-project entries
         * allow everything.
         */
        public boolean pathAllowed(String docPath) {
            String normalized = stripLeadingSlash(docPath);
            for (String prefix : paths) {
                if (normalized.startsWith(prefix)) return true;
            }
            return isWholeProject();
        }
    }

    /**
     * First entry that applies to the caller, or {@code null} (= deny).
     * Document order decides; see class javadoc.
     */
    public @Nullable Entry resolve(McpCaller caller) {
        for (Entry entry : access) {
            if (entry.matches(caller)) return entry;
        }
        return null;
    }

    /**
     * Parses the YAML body of the config document. Malformed content is
     * an {@link IllegalStateException} — the access service turns that
     * into a warn log plus deny, never a 5xx.
     */
    public static McpProjectAccessConfig parse(String yamlContent) {
        Object parsed = new Yaml().load(yamlContent);
        if (parsed == null) {
            throw new IllegalStateException("mcp-access YAML is empty");
        }
        if (!(parsed instanceof Map<?, ?> rawMap)) {
            throw new IllegalStateException("mcp-access YAML must have a top-level map");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> spec = (Map<String, Object>) rawMap;

        Object def = spec.get("default");
        if (def != null && !"deny".equals(String.valueOf(def).trim().toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException(
                    "mcp-access: 'default' exists only to document intent and must be 'deny', got '" + def + "'");
        }

        Object accessRaw = spec.get("access");
        if (accessRaw == null) {
            throw new IllegalStateException("mcp-access YAML needs an 'access' list");
        }
        if (!(accessRaw instanceof List<?> rawList)) {
            throw new IllegalStateException("mcp-access 'access' must be a list");
        }
        List<Entry> entries = new java.util.ArrayList<>(rawList.size());
        for (Object item : rawList) {
            if (!(item instanceof Map<?, ?> rawEntry)) {
                throw new IllegalStateException("mcp-access 'access' entries must be maps");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> entrySpec = (Map<String, Object>) rawEntry;
            entries.add(parseEntry(entrySpec));
        }
        return new McpProjectAccessConfig(entries);
    }

    private static Entry parseEntry(Map<String, Object> spec) {
        String name = stringOrNull(spec.get("name"));
        McpCallerFilter caller = McpCallerFilter.parse(spec, String.valueOf(name));
        String modeWire = stringOrNull(spec.get("mode"));
        Mode mode = modeWire == null ? null : Mode.fromWire(modeWire);
        List<String> paths = stringList(spec.get("paths"));
        return new Entry(name, caller, mode, paths);
    }

    private static @Nullable String stringOrNull(Object value) {
        return value instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> raw)) return List.of();
        List<String> out = new java.util.ArrayList<>(raw.size());
        for (Object item : raw) {
            if (item instanceof String s && !s.isBlank()) out.add(s.trim());
        }
        return List.copyOf(out);
    }

    private static List<String> normalizePrefixes(List<String> prefixes) {
        List<String> out = new java.util.ArrayList<>(prefixes.size());
        for (String prefix : prefixes) {
            String normalized = stripLeadingSlash(prefix.trim());
            if (normalized.isEmpty()) continue; // "" = whole project
            if (!normalized.endsWith("/")) normalized = normalized + "/";
            out.add(normalized);
        }
        return List.copyOf(out);
    }

    private static String stripLeadingSlash(String path) {
        String trimmed = path.trim();
        while (trimmed.startsWith("/")) trimmed = trimmed.substring(1);
        return trimmed;
    }
}
