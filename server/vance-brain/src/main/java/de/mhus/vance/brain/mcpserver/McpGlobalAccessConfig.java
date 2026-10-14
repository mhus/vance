package de.mhus.vance.brain.mcpserver;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.Yaml;

/**
 * The parsed shape of {@code _vance/config/mcp-global-access.yaml} in the
 * {@code _tenant} project — who may reach the global MCP door
 * ({@code POST /brain/{tenant}/mcp}) with a <em>narrowed</em> credential: an
 * OAuth grant for the door, or an integration token of the {@code mcp}
 * profile.
 *
 * <p>Same "who" grammar as {@code mcp-access.yaml} ({@link McpCallerFilter}:
 * {@code token}, {@code oauth}, {@code users}; first match wins), but no
 * {@code mode}/{@code paths}: the global door has no document surface to cut —
 * behind it is the full tool catalogue under the account's grants.
 *
 * <p>Fail-closed: a missing or malformed file lets no narrowed credential in.
 * The full-account login token is not governed by this file.
 */
public record McpGlobalAccessConfig(List<Entry> access) {

    public static final McpGlobalAccessConfig EMPTY = new McpGlobalAccessConfig(List.of());

    public McpGlobalAccessConfig {
        access = access == null ? List.of() : List.copyOf(access);
    }

    /** One rule: a name for logs, and who it admits. */
    public record Entry(String name, McpCallerFilter caller) {

        public Entry {
            if (name == null || name.isBlank()) {
                throw new IllegalStateException("access entry needs a non-blank 'name'");
            }
            if (caller == null) caller = McpCallerFilter.ANY;
        }
    }

    /** First entry admitting {@code caller}, or {@code null} = deny. */
    public @Nullable Entry resolve(McpCaller caller) {
        for (Entry entry : access) {
            if (entry.caller().matches(caller)) return entry;
        }
        return null;
    }

    /** Parses the YAML body; malformed content is an {@link IllegalStateException}. */
    public static McpGlobalAccessConfig parse(String yamlContent) {
        Object parsed = new Yaml().load(yamlContent);
        if (!(parsed instanceof Map<?, ?> rawMap)) {
            throw new IllegalStateException("mcp-global-access YAML must have a top-level map");
        }
        Object def = rawMap.get("default");
        if (def != null && !"deny".equals(String.valueOf(def).trim().toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException(
                    "mcp-global-access: 'default' exists only to document intent and must be 'deny', got '" + def
                            + "'");
        }
        if (!(rawMap.get("access") instanceof List<?> rawList)) {
            throw new IllegalStateException("mcp-global-access YAML needs an 'access' list");
        }
        List<Entry> entries = new ArrayList<>(rawList.size());
        for (Object item : rawList) {
            if (!(item instanceof Map<?, ?> rawEntry)) {
                throw new IllegalStateException("mcp-global-access 'access' entries must be maps");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> spec = (Map<String, Object>) rawEntry;
            String name = spec.get("name") instanceof String s && !s.isBlank() ? s.trim() : null;
            if (spec.containsKey("mode") || spec.containsKey("paths")) {
                throw new IllegalStateException("mcp-global-access entry '" + name
                        + "': 'mode'/'paths' belong to mcp-access.yaml — the global door has no document surface");
            }
            entries.add(new Entry(name, McpCallerFilter.parse(spec, String.valueOf(name))));
        }
        return new McpGlobalAccessConfig(entries);
    }
}
