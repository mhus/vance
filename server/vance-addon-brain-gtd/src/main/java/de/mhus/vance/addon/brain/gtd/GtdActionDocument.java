package de.mhus.vance.addon.brain.gtd;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * In-memory model of a {@code kind: action} document — one GTD next-action.
 *
 * @param kind     always {@code "action"}.
 * @param title    short headline; falls back to the filename stem.
 * @param when     bucket driver: {@code ""} (Anytime), {@code "today"},
 *                 {@code "someday"}, or an ISO date (Upcoming/Today). See
 *                 {@link GtdBucketResolver}.
 * @param deadline optional hard due date (ISO); pulls the action into Today
 *                 on/after the date, independent of {@code when}.
 * @param contexts GTD contexts ({@code @calls}/{@code @errands}/…); mirrored
 *                 onto the native document tag set for search.
 * @param done     completed — drops out of every bucket.
 * @param body     Markdown note + GFM subtasks.
 * @param extra    unknown front-matter keys, passthrough.
 */
public record GtdActionDocument(
        String kind,
        String title,
        String when,
        @Nullable String deadline,
        List<String> contexts,
        boolean done,
        String body,
        Map<String, Object> extra) {

    public static final String KIND = "action";

    public GtdActionDocument {
        if (kind == null || kind.isBlank()) kind = KIND;
        if (title == null) title = "";
        if (when == null) when = "";
        if (contexts == null) contexts = new ArrayList<>();
        if (body == null) body = "";
        if (extra == null) extra = new LinkedHashMap<>();
    }

    public static GtdActionDocument empty() {
        return new GtdActionDocument(KIND, "", "", null,
                new ArrayList<>(), false, "", new LinkedHashMap<>());
    }
}
