package de.mhus.vance.brain.webui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The address of a web UI asset, checked: what the request names becomes a cascade path only through here. A segment
 * is a plain lowercase name, the file ends in a type the server serves, and nothing in it can climb out of
 * {@code _vance/webui/} — see {@code webui-assets.md} §2 and §3.
 *
 * @param cascadePath the document path to look up, {@code _vance/webui/<segments>/<file>}
 * @param type        what the file is, by its ending
 */
record WebuiAssetPath(String cascadePath, Type type) {

    /** The folder every asset lives in. */
    static final String ROOT = "_vance/webui/";

    /** More segments than this are no asset of ours. */
    static final int MAX_SEGMENTS = 6;

    private static final Pattern SEGMENT = Pattern.compile("[a-z0-9][a-z0-9._-]*");

    /** What the server serves, by file ending. */
    enum Type {
        SVG("image/svg+xml"),
        PNG("image/png"),
        JPEG("image/jpeg"),
        WEBP("image/webp"),
        GIF("image/gif"),
        ICO("image/x-icon"),
        WOFF2("font/woff2");

        private final String contentType;

        Type(String contentType) {
            this.contentType = contentType;
        }

        String contentType() {
            return contentType;
        }
    }

    private static final Map<String, Type> BY_ENDING = Map.of(
            "svg", Type.SVG,
            "png", Type.PNG,
            "jpg", Type.JPEG,
            "jpeg", Type.JPEG,
            "webp", Type.WEBP,
            "gif", Type.GIF,
            "ico", Type.ICO,
            "woff2", Type.WOFF2);

    /**
     * @param requested the path after {@code /webui/}, with or without a leading slash
     * @return the checked address, or empty when it is not one the server serves
     */
    static Optional<WebuiAssetPath> parse(String requested) {
        String trimmed = requested.startsWith("/") ? requested.substring(1) : requested;
        if (trimmed.isEmpty()) return Optional.empty();
        List<String> segments = new ArrayList<>(List.of(trimmed.split("/", -1)));
        if (segments.size() > MAX_SEGMENTS) return Optional.empty();
        for (String segment : segments) {
            if (!SEGMENT.matcher(segment).matches() || segment.equals("..") || segment.contains("..")) {
                return Optional.empty();
            }
        }
        String file = segments.get(segments.size() - 1);
        int dot = file.lastIndexOf('.');
        if (dot <= 0 || dot == file.length() - 1) return Optional.empty();
        Type type = BY_ENDING.get(file.substring(dot + 1).toLowerCase(Locale.ROOT));
        if (type == null) return Optional.empty();
        return Optional.of(new WebuiAssetPath(ROOT + String.join("/", segments), type));
    }
}
