package de.mhus.vance.api.hooks;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Canonical names of events that hooks may subscribe to. The string
 * values are also the path component under
 * {@code _vance/hooks/<event>/<name>.yaml}.
 *
 * <p>See {@code specification/hooks.md} §3 for the catalog.
 */
@GenerateTypeScript("hooks")
public enum HookEventName {
    PROCESS_COMPLETED("process.completed"),
    PROCESS_FAILED("process.failed"),
    INBOX_ITEM_CREATED("inbox.item.created"),

    /**
     * Reserved — no trigger emitter wired yet. The catalog entry is
     * accepted so hook documents can already be authored; the event
     * will start firing once {@code SessionService} publishes a
     * Spring application event on suspend.
     */
    SESSION_SUSPENDED("session.suspended"),

    /** Reserved — same status as {@link #SESSION_SUSPENDED}. */
    SESSION_RESUMED("session.resumed"),

    /**
     * Reserved — the knowledge-graph service layer that fires this
     * event does not exist yet. Hooks for this event are valid
     * documents but will simply never fire in v1.
     */
    INSIGHT_SAVED("insight.saved"),

    /** Reserved — same status as {@link #INSIGHT_SAVED}. */
    RELATION_CREATED("relation.created");

    private final String wireName;

    HookEventName(String wireName) {
        this.wireName = wireName;
    }

    /** Path-component / source-key form, e.g. {@code "process.completed"}. */
    public String wireName() {
        return wireName;
    }

    public static HookEventName ofWire(String name) {
        for (HookEventName e : values()) {
            if (e.wireName.equals(name)) return e;
        }
        throw new IllegalArgumentException("Unknown hook event: '" + name + "'");
    }

    public static boolean isKnown(String name) {
        for (HookEventName e : values()) {
            if (e.wireName.equals(name)) return true;
        }
        return false;
    }
}
