package de.mhus.vance.brain.trillian.exposure;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.Yaml;

/**
 * The parsed shape of {@code _vance/trillian/exposure.yaml} — the exposure
 * configuration a Trillian carries in its hub
 * ({@code planning/trillian-exposure.md} §3.4). A Trillian with no such
 * document is not exposed; a malformed one is not exposed either —
 * fail-closed, like every other config document that opens a door.
 *
 * <p><b>v1 fields.</b> {@code exposedName} is the public name a counterpart
 * addresses ({@code agent_send(name: "colin")}); {@code description} is what
 * the directory shows; {@code allowedCounterparts} is the gate — the accounts
 * allowed to open conversations, checked against the caller's identity at
 * request time, never mapped from a config identity (maximegalon §9).
 * {@code headLimit} lowers the Nature's head limit when greater than zero
 * (it never lifts it — the Nature's limit is the bound on cost and flood
 * abuse); {@code idleMinutes} overrides the server's idle window (how long a
 * conversation may sit with nothing owed and nothing running before it is
 * closed) when greater than zero. {@code
 * doors} says which shells may stand in front of the protocol entry — the
 * MCP door ({@code POST /brain/{tenant}/agents}) is the first.
 *
 * <p>Numbers are {@code int}-typed with {@code 0} as the documented "not
 * set" value; YAML arrives as Integer/Long either way.
 */
public record TrillianExposureConfig(
        String exposedName,
        String description,
        List<String> allowedCounterparts,
        int headLimit,
        int idleMinutes,
        Doors doors) {

    /** Which shells in front of the protocol entry are open. */
    public record Doors(boolean mcp, boolean a2a) {

        static final Doors NONE = new Doors(false, false);
    }

    /**
     * Parses the YAML body. Anything malformed throws — the caller treats an
     * exception as "not exposed", the same as a missing document, so a broken
     * config opens nothing.
     */
    public static TrillianExposureConfig parse(String yamlContent) {
        Object parsed = new Yaml().load(yamlContent);
        if (!(parsed instanceof Map<?, ?> root)) {
            throw new IllegalStateException("exposure YAML is empty or not a mapping");
        }
        String exposedName = stringOf(root.get("exposedName"));
        if (exposedName.isBlank()) {
            throw new IllegalStateException("exposure YAML has no exposedName");
        }
        String description = stringOf(root.get("description"));
        List<String> allowed = listOf(root.get("allowedCounterparts"));
        Doors doors = root.get("doors") instanceof Map<?, ?> doorsMap
                ? new Doors(boolOf(doorsMap.get("mcp")), boolOf(doorsMap.get("a2a")))
                : Doors.NONE;
        return new TrillianExposureConfig(
                exposedName, description, allowed, intOf(root.get("headLimit")), intOf(root.get("idleMinutes")), doors);
    }

    /** Whether {@code account} may open conversations with this Trillian. */
    public boolean allows(String account) {
        return allowedCounterparts.contains(account);
    }

    private static String stringOf(@Nullable Object raw) {
        return raw instanceof String s ? s.trim() : "";
    }

    private static int intOf(@Nullable Object raw) {
        return raw instanceof Number n ? n.intValue() : 0;
    }

    private static boolean boolOf(@Nullable Object raw) {
        return raw instanceof Boolean b && b;
    }

    private static List<String> listOf(@Nullable Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        return list.stream()
                .map(TrillianExposureConfig::stringOf)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
