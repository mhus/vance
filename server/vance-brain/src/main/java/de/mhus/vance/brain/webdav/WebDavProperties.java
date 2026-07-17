package de.mhus.vance.brain.webdav;

import java.time.Duration;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the WebDAV surface ({@code /brain/{tenant}/webdav/…}).
 *
 * <p>The WebDAV feature itself is gated on {@code vance.redis.enabled=true} —
 * cross-pod lock state lives in Redis, so without Redis the surface is not
 * registered at all (Brain still boots; WebDAV is simply unavailable). See
 * {@code planning/webdav-support.md} §5.
 */
@Data
@ConfigurationProperties(prefix = "vance.webdav")
public class WebDavProperties {

    /** Basic-Auth realm presented in the {@code WWW-Authenticate} challenge. */
    private String realm = "Vance";

    /**
     * Retention for the hidden {@code .vancedir} folder-marker document that
     * {@code MKCOL} writes so an empty directory survives (folders are virtual
     * — derived from document path prefixes — so an empty one has nothing to
     * anchor it otherwise). Backed by the document TTL index; once real files
     * live in the folder the marker is irrelevant and expires harmlessly. See
     * {@code planning/webdav-support.md} §8.1.
     */
    private Duration folderMarkerTtl = Duration.ofDays(1);

    /**
     * Retention for the opaque macOS sidecar blobs ({@code .DS_Store},
     * {@code ._*}, …) cached in Redis so Finder folder-view state survives
     * across requests and pods. Best-effort / cosmetic — expiry just resets
     * the folder view. See {@code planning/webdav-support.md} §6.
     */
    private Duration sidecarTtl = Duration.ofDays(1);

    /** Default lock timeout handed back on a LOCK request without a timeout. */
    private Duration lockTimeout = Duration.ofHours(1);

    /** Upper bound on a client-requested lock timeout. */
    private Duration lockTimeoutMax = Duration.ofHours(12);

    /**
     * Filename glob patterns treated as client-noise sidecars: hidden from
     * listings, served on direct GET from the Redis cache, accepted-and-cached
     * on PUT (never persisted as real documents). Matched against the leaf
     * name only. Covers the paths Finder probes on mount plus Windows junk.
     */
    private List<String> sidecarPatterns = List.of(
            ".DS_Store",
            "._*",
            ".Spotlight-V100",
            ".Trashes",
            ".fseventsd",
            ".TemporaryItems",
            ".hidden",
            ".metadata_never_index",
            ".VolumeIcon.icns",
            ".apdisk",
            "Thumbs.db",
            "desktop.ini");

    /** Leaf name of the hidden folder-marker document (also treated as hidden). */
    private String folderMarkerName = ".vancedir";

    /**
     * {@code true} when {@code leafName} matches one of the {@link #sidecarPatterns}
     * — i.e. it is a client-noise file routed to the Redis {@link SidecarStore}
     * rather than the document store. Only {@code *} wildcards are supported
     * (prefix/suffix/contains); that is all the real patterns need.
     */
    public boolean isSidecar(String leafName) {
        for (String pattern : sidecarPatterns) {
            if (globMatches(pattern, leafName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code true} when {@code leafName} must not appear in a directory listing:
     * sidecars (§6) plus the folder-marker (§8.1). System {@code _}-prefixes are
     * deliberately NOT hidden in v1 (§8.3).
     */
    public boolean isHidden(String leafName) {
        return folderMarkerName.equals(leafName) || isSidecar(leafName);
    }

    private static boolean globMatches(String pattern, String value) {
        int star = pattern.indexOf('*');
        if (star < 0) {
            return pattern.equals(value);
        }
        String prefix = pattern.substring(0, star);
        String suffix = pattern.substring(star + 1);
        return value.length() >= prefix.length() + suffix.length()
                && value.startsWith(prefix)
                && value.endsWith(suffix);
    }
}
