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
 * surface offers fixed tool families ({@link McpProjectCapability});
 * the config decides per caller which families are visible at all
 * ({@code capabilities:}, none by default), whether the writing tools
 * among them are ({@link Mode}), and to which path prefixes the document
 * tools apply. Authorization against the account's
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
     * @param capabilities the tool families this entry grants; <b>empty = nothing</b> — an entry without
     *               {@code capabilities:} still matches its callers (so it cannot be skipped over by a
     *               later entry) but offers no tool. There is no default capability.
     * @param paths  normalized path prefixes for the {@code doc} capability; empty = the whole project
     *               (writes into {@code _vance/} stay denied regardless)
     * @param dirs   names of the workspace RootDirs the {@code file} and {@code exec} capabilities may
     *               touch; <b>required</b> for those two (there is no "every directory"), meaningless —
     *               and refused — without them
     */
    public record Entry(
            String name,
            McpCallerFilter caller,
            Mode mode,
            List<McpProjectCapability> capabilities,
            List<String> paths,
            List<String> dirs) {

        /** A RootDir name: what the workspace store itself accepts as one path segment (no '/', no '..'). */
        private static final java.util.regex.Pattern DIR_NAME =
                java.util.regex.Pattern.compile("[a-zA-Z0-9_][a-zA-Z0-9._-]*");

        /** An entry that confines no workspace directories — everything but {@code file}/{@code exec}. */
        public Entry(
                String name,
                McpCallerFilter caller,
                Mode mode,
                List<McpProjectCapability> capabilities,
                List<String> paths) {
            this(name, caller, mode, capabilities, paths, List.of());
        }

        public Entry {
            if (name == null || name.isBlank()) {
                throw new IllegalStateException("access entry needs a non-blank 'name'");
            }
            if (mode == null) {
                throw new IllegalStateException("access entry '" + name + "' needs a 'mode'");
            }
            if (caller == null) caller = McpCallerFilter.ANY;
            capabilities = capabilities == null
                    ? List.of()
                    : capabilities.stream().distinct().toList();
            if (paths == null) paths = List.of();
            paths = normalizePrefixes(paths);
            // Path prefixes confine document paths. A workflow run has none, so a prefix-confined entry
            // that also granted workflow tools would hand out the whole project's runs and their
            // variables under a rule that reads as narrower. Refused rather than guessed at.
            if (capabilities.contains(McpProjectCapability.WORKFLOW) && !paths.isEmpty()) {
                throw new IllegalStateException("access entry '" + name + "' grants 'workflow' but has 'paths' —"
                        + " workflow runs are not confined by document prefixes; put them in a separate entry");
            }
            dirs = dirs == null
                    ? List.of()
                    : dirs.stream().map(String::trim).distinct().toList();
            for (String dir : dirs) {
                if (!DIR_NAME.matcher(dir).matches()) {
                    throw new IllegalStateException("access entry '" + name + "': '" + dir
                            + "' is not a workspace directory name (letters, digits, '.', '_', '-')");
                }
            }
            boolean workspace = capabilities.contains(McpProjectCapability.FILE)
                    || capabilities.contains(McpProjectCapability.EXEC);
            // The directories are the confinement of these two families, like the prefixes are for
            // documents. Without them an entry would mean "every directory of the project" — a reading
            // this door does not have. And a list that nothing reads would look like a confinement it is not.
            if (workspace && dirs.isEmpty()) {
                throw new IllegalStateException("access entry '" + name + "' grants 'file' or 'exec' but names no"
                        + " 'dirs' — list the workspace directories it may use");
            }
            if (!workspace && !dirs.isEmpty()) {
                throw new IllegalStateException("access entry '" + name
                        + "' has 'dirs' but grants neither 'file' nor 'exec' — they confine nothing else");
            }
            if (capabilities.contains(McpProjectCapability.EXEC)) {
                // A command has no read-only form, and arbitrary code in the project's sandbox is not
                // something to offer to "whoever the entry happens to match".
                if (mode == Mode.RO) {
                    throw new IllegalStateException("access entry '" + name
                            + "' grants 'exec' with mode 'ro' — running a command is not read-only; use 'rw'");
                }
                if (caller.token() == null && caller.users().isEmpty()) {
                    throw new IllegalStateException("access entry '" + name + "' grants 'exec' to every caller —"
                            + " name a 'token' or 'users' for it");
                }
            }
        }

        /** Whether this entry offers anything at all — an entry without capabilities does not. */
        public boolean grantsAnything() {
            return !capabilities.isEmpty();
        }

        /** Whether this entry grants the tool family. */
        public boolean allows(McpProjectCapability capability) {
            return capabilities.contains(capability);
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
        List<McpProjectCapability> capabilities = parseCapabilities(spec.get("capabilities"), String.valueOf(name));
        List<String> paths = stringList(spec.get("paths"));
        Object dirsRaw = spec.get("dirs");
        if (dirsRaw != null && !(dirsRaw instanceof List<?>)) {
            throw new IllegalStateException("access entry '" + name + "': 'dirs' must be a list");
        }
        return new Entry(name, caller, mode, capabilities, paths, stringList(dirsRaw));
    }

    /**
     * The {@code capabilities:} list. Absent or empty means <em>no capability</em>, not "all": there is
     * no legacy reading of an entry that predates the key. A value that is not a list of non-blank
     * strings, or names a capability that does not exist, is a load error — a typo must not leave an
     * entry looking like it grants something.
     */
    private static List<McpProjectCapability> parseCapabilities(@Nullable Object raw, String entryName) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) {
            throw new IllegalStateException("access entry '" + entryName + "': 'capabilities' must be a list");
        }
        List<McpProjectCapability> out = new java.util.ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof String s) || s.isBlank()) {
                throw new IllegalStateException(
                        "access entry '" + entryName + "': 'capabilities' entries must be non-blank strings");
            }
            try {
                out.add(McpProjectCapability.fromWire(s));
            } catch (IllegalStateException ex) {
                throw new IllegalStateException("access entry '" + entryName + "': " + ex.getMessage(), ex);
            }
        }
        return List.copyOf(out);
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
