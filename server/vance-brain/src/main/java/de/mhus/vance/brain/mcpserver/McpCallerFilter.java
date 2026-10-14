package de.mhus.vance.brain.mcpserver;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The "who" part of an MCP access entry — shared by {@code mcp-access.yaml}
 * (project door) and {@code mcp-global-access.yaml} (global door) so both
 * read the same way.
 *
 * <p>All set filters must match:
 * <ul>
 *   <li>{@code token} — only the integration token with this id;</li>
 *   <li>{@code oauth: true} — only OAuth grants; {@code oauth: false} —
 *       everyone except OAuth grants; absent — no restriction;</li>
 *   <li>{@code users} — only these accounts.</li>
 * </ul>
 * {@code token} together with {@code oauth: true} can never match anyone and
 * is rejected at parse time — a config that looks like it opens something but
 * opens nothing is a mistake worth a loud failure.
 *
 * @param token integration-token id, or {@code null}
 * @param oauth {@code true}/{@code false}/{@code null} as above
 * @param users account names; empty = any account
 */
public record McpCallerFilter(
        @Nullable String token, @Nullable Boolean oauth, List<String> users) {

    public static final McpCallerFilter ANY = new McpCallerFilter(null, null, List.of());

    public McpCallerFilter {
        users = users == null ? List.of() : List.copyOf(users);
        if (token != null && Boolean.TRUE.equals(oauth)) {
            throw new IllegalStateException(
                    "an entry cannot combine 'token' with 'oauth: true' — it would match nobody");
        }
    }

    public boolean matches(McpCaller caller) {
        if (token != null && !(caller.kind() == McpCaller.Kind.INTEGRATION && token.equals(caller.tokenId()))) {
            return false;
        }
        if (oauth != null && oauth != (caller.kind() == McpCaller.Kind.OAUTH)) {
            return false;
        }
        return users.isEmpty() || (caller.username() != null && users.contains(caller.username()));
    }

    /** Read the filter fields of one YAML entry map. */
    static McpCallerFilter parse(Map<String, Object> spec, String entryName) {
        String token = spec.get("token") instanceof String s && !s.isBlank() ? s.trim() : null;
        Object rawOauth = spec.get("oauth");
        Boolean oauth;
        if (rawOauth == null) {
            oauth = null;
        } else if (rawOauth instanceof Boolean b) {
            oauth = b;
        } else {
            throw new IllegalStateException("entry '" + entryName + "': 'oauth' must be true or false");
        }
        List<String> users = new ArrayList<>();
        Object rawUsers = spec.get("users");
        if (rawUsers instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String s && !s.isBlank()) users.add(s.trim());
            }
        } else if (rawUsers instanceof String single && !single.isBlank()) {
            users.add(single.trim());
        } else if (rawUsers != null) {
            throw new IllegalStateException("entry '" + entryName + "': 'users' must be a list of account names");
        }
        try {
            return new McpCallerFilter(token, oauth, users);
        } catch (IllegalStateException e) {
            throw new IllegalStateException("entry '" + entryName + "': " + e.getMessage(), e);
        }
    }
}
