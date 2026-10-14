package de.mhus.vance.brain.oauthserver;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Maps between doors, their canonical resource URLs and request paths.
 *
 * <p>The canonical resource URL of a door is the issuer of its tenant plus
 * the door path — {@code https://host/brain/acme/mcp/docs}. A {@code resource}
 * parameter is accepted when it denotes exactly that URL; scheme and host are
 * compared case-insensitively and a trailing slash is ignored, as the MCP
 * specification asks for robustness, everything else must match.
 */
@Component
@RequiredArgsConstructor
public class OAuthDoors {

    /** A door path as it appears in a request URI, with the tenant in group 1. */
    private static final Pattern DOOR_REQUEST_PATH = Pattern.compile("^/brain/([^/]+)/(mcp|agents)(?:/([^/]+))?/?$");

    /** Door path relative to the tenant root. */
    private static final Pattern DOOR_PATH = Pattern.compile("^/(mcp|agents)(?:/([^/]+))?$");

    /** A door addressed by a request, with the tenant it belongs to. */
    public record TenantDoor(String tenant, OAuthDoor door) {}

    private final OAuthServerSettings settings;

    /** Canonical resource URL of the door. */
    public String resourceUrl(String tenant, OAuthDoor door) {
        return settings.issuer(tenant) + door.path();
    }

    /** URL of the door's Protected Resource Metadata (RFC 9728), as advertised in 401 challenges. */
    public String metadataUrl(String tenant, OAuthDoor door) {
        return settings.issuer(tenant) + "/oauth/resource" + door.path();
    }

    /** The door a request URI addresses, or {@code null}. */
    public static @Nullable TenantDoor fromRequestPath(String requestUri) {
        Matcher matcher = DOOR_REQUEST_PATH.matcher(requestUri);
        if (!matcher.matches()) {
            return null;
        }
        OAuthDoor door = door(matcher.group(2), matcher.group(3));
        return door == null ? null : new TenantDoor(matcher.group(1), door);
    }

    /**
     * The door a {@code resource} parameter denotes for {@code tenant}, or
     * {@code null} when it is not one of this tenant's doors.
     */
    public @Nullable OAuthDoor fromResource(String tenant, String resource) {
        URI given;
        URI issuer;
        try {
            given = URI.create(resource.trim());
            issuer = URI.create(settings.issuer(tenant));
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (given.getScheme() == null
                || given.getHost() == null
                || given.getRawQuery() != null
                || given.getRawFragment() != null
                || given.getRawUserInfo() != null) {
            return null;
        }
        if (!given.getScheme().equalsIgnoreCase(issuer.getScheme())
                || !given.getHost().equalsIgnoreCase(issuer.getHost())
                || effectivePort(given) != effectivePort(issuer)) {
            return null;
        }
        String path = given.getRawPath() == null ? "" : given.getRawPath();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        String issuerPath = issuer.getRawPath();
        if (!path.startsWith(issuerPath + "/")) {
            return null;
        }
        Matcher matcher = DOOR_PATH.matcher(path.substring(issuerPath.length()));
        if (!matcher.matches()) {
            return null;
        }
        return door(matcher.group(1), matcher.group(2));
    }

    private static @Nullable OAuthDoor door(String kind, @Nullable String project) {
        return switch (kind.toLowerCase(Locale.ROOT)) {
            case "mcp" -> project == null ? OAuthDoor.mcp() : OAuthDoor.mcpProject(project);
            case "agents" -> project == null ? OAuthDoor.agents() : null;
            default -> null;
        };
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
