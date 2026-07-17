package de.mhus.vance.brain.webdav;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Parses the WebDAV URL shape {@code /brain/{tenant}/webdav/{project}/{path...}}
 * into its three coordinates. The document {@code path} is the slash-separated
 * logical path inside the project (may be empty → project root collection).
 */
public final class WebDavPaths {

    private WebDavPaths() {}

    /** Matches the WebDAV surface; group 1 = tenant, group 2 = everything after {@code /webdav/}. */
    static final Pattern WEBDAV_PATH = Pattern.compile("^/brain/([^/]+)/webdav(?:/(.*))?$");

    /**
     * @param project {@code null} when the URL addresses the bare
     *                {@code /brain/{tenant}/webdav} root (no project selected —
     *                not a browsable resource in v1).
     * @param path    document path inside the project; {@code ""} for the
     *                project root collection.
     */
    public record Coords(String tenantId, @Nullable String project, String path) {}

    public static Optional<Coords> parse(String absolutePath) {
        String p = absolutePath;
        int q = p.indexOf('?');
        if (q >= 0) {
            p = p.substring(0, q);
        }
        Matcher m = WEBDAV_PATH.matcher(p);
        if (!m.matches()) {
            return Optional.empty();
        }
        String tenant = decodeSegment(m.group(1));
        String rest = m.group(2);
        if (rest == null) {
            return Optional.of(new Coords(tenant, null, ""));
        }
        while (rest.endsWith("/")) {
            rest = rest.substring(0, rest.length() - 1);
        }
        if (rest.isEmpty()) {
            return Optional.of(new Coords(tenant, null, ""));
        }
        int slash = rest.indexOf('/');
        if (slash < 0) {
            return Optional.of(new Coords(tenant, decodeSegment(rest), ""));
        }
        String project = decodeSegment(rest.substring(0, slash));
        String docPath = decodePath(rest.substring(slash + 1));
        return Optional.of(new Coords(tenant, project, docPath));
    }

    /** Decode each path segment individually so an encoded {@code %2F} never collapses a boundary. */
    private static String decodePath(String path) {
        String[] segments = path.split("/");
        StringBuilder out = new StringBuilder(path.length());
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                out.append('/');
            }
            out.append(decodeSegment(segments[i]));
        }
        return out.toString();
    }

    private static String decodeSegment(String segment) {
        return URLDecoder.decode(segment, StandardCharsets.UTF_8);
    }
}
