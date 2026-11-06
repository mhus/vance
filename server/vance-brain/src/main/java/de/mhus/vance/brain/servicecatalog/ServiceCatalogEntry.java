package de.mhus.vance.brain.servicecatalog;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One service of the catalog, parsed from {@code _vance/service-catalog/<name>.yaml}:
 *
 * <pre>
 * title: { en: "Jira", de: "Jira" }
 * description: { en: "…", de: "…" }
 * icon: ticket
 * match: [jira]                     # pack names this entry describes — "jira" and "jira_rest" both match
 * login:
 *   provider: atlassian             # the OAuth provider whose login of the calling user the tools need
 *   manual: oauth-tools             # the manual that says how to connect it
 * highlights:
 *   - tool: createIssue             # an operation of the pack, by the part after the pack's prefix
 *     description: { en: "…" }
 *     params: { body: { fields: { … } } }     # an example for a form to start from
 * </pre>
 *
 * @param name        file name without {@code .yaml}
 * @param title       per language
 * @param description per language
 * @param icon        an icon name of the web UI, or {@code null}
 * @param match       pack names (the {@code service} of a tool) the entry describes
 * @param provider    OAuth provider id, or {@code null} when the tools need no user login
 * @param manual      manual name, or {@code null}
 * @param highlights  the operations people reach for first
 */
public record ServiceCatalogEntry(
        String name,
        Map<String, String> title,
        Map<String, String> description,
        @Nullable String icon,
        List<String> match,
        @Nullable String provider,
        @Nullable String manual,
        List<Highlight> highlights) {

    /** An operation worth pointing at. */
    public record Highlight(
            String tool,
            Map<String, String> description,
            @Nullable Map<String, Object> params) {}

    /**
     * Whether this entry describes the pack {@code service}: its name equals a {@code match} entry, or continues
     * it with an underscore ({@code jira_rest} for {@code jira}). Case-insensitive.
     */
    public boolean describes(String service) {
        String lower = service.toLowerCase(Locale.ROOT);
        for (String m : match) {
            String mm = m.toLowerCase(Locale.ROOT);
            if (lower.equals(mm) || lower.startsWith(mm + "_")) return true;
        }
        return false;
    }

    /** The text in {@code lang}, else English, else any, else empty. */
    public static String localized(Map<String, String> texts, String lang) {
        if (texts.isEmpty()) return "";
        String exact = texts.get(lang);
        if (exact != null) return exact;
        String english = texts.get("en");
        return english != null ? english : texts.values().iterator().next();
    }
}
