package de.mhus.vance.addon.brain.links.tool;

import de.mhus.vance.addon.brain.links.LinksManifestOps;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Shared param reading for the {@code links_*} tool family. */
final class LinksToolSupport {

    private LinksToolSupport() {}

    static @Nullable String paramString(@Nullable Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    /**
     * Read a string list, tolerating the single string a model hands over
     * when the schema says array. Returns {@code null} when the key is
     * absent — which the update path reads as "leave the tags alone", so
     * conflating it with an empty list would silently wipe them.
     */
    static @Nullable List<String> paramStringList(@Nullable Map<String, Object> params, String key) {
        if (params == null || !params.containsKey(key)) return null;
        Object v = params.get(key);
        if (v == null) return null;
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String s && !s.isBlank()) out.add(s.trim());
            }
        } else if (v instanceof String s) {
            for (String part : s.split(",")) {
                if (!part.isBlank()) out.add(part.trim());
            }
        }
        return out;
    }

    /**
     * The field bundle for add/update. {@code image} is deliberately not a
     * tool parameter: a model asked for a picture would invent a plausible
     * URL, and the picture is exactly the field that resolves itself from
     * the page. Setting one by hand stays a UI action.
     */
    static LinksManifestOps.LinkFields fields(Map<String, Object> params) {
        return new LinksManifestOps.LinkFields(
                rawString(params, "title"),
                rawString(params, "teaser"),
                null,
                rawString(params, "group"),
                paramStringList(params, "tags"),
                rawString(params, "note"));
    }

    /**
     * Like {@link #paramString} but keeps a deliberately empty string: the
     * update path reads blank as "clear this field", and trimming it to
     * null would make clearing impossible from a tool call.
     */
    static @Nullable String rawString(@Nullable Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        return v instanceof String s ? s.trim() : null;
    }
}
