package de.mhus.vance.shared.megadodo;

import de.mhus.vance.api.megadodo.MegadodoRefType;
import de.mhus.vance.api.megadodo.MegadodoSeverity;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Filter for one feed page. A record rather than a ten-argument method —
 * every field is optional and callers set two or three of them.
 *
 * @param tenantId    required
 * @param projectId   {@code null} = every project the caller may see,
 *                    plus tenant-wide rows
 * @param from        inclusive lower bound on {@code timestamp}
 * @param to          exclusive upper bound on {@code timestamp}
 * @param minSeverity rows below this are dropped ({@code ERROR} = the
 *                    "only failures" switch)
 * @param actionPrefix matches {@code action} by prefix, e.g. {@code scheduler.}
 * @param refType     narrow to one kind of thing
 * @param refId       narrow to one thing — only meaningful with refType
 * @param actor       who caused it
 * @param text        case-insensitive substring of {@code message}
 * @param cursor      opaque keyset cursor from the previous page
 * @param limit       clamped to {@code [1, 200]}
 */
public record MegadodoQuery(
        String tenantId,
        @Nullable String projectId,
        @Nullable Instant from,
        @Nullable Instant to,
        @Nullable MegadodoSeverity minSeverity,
        @Nullable String actionPrefix,
        @Nullable MegadodoRefType refType,
        @Nullable String refId,
        @Nullable String actor,
        @Nullable String text,
        @Nullable String cursor,
        int limit) {

    public MegadodoQuery {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("MegadodoQuery.tenantId must be non-blank");
        }
    }

    /** Everything of one project, newest first. */
    public static MegadodoQuery ofProject(String tenantId, @Nullable String projectId, int limit) {
        return new MegadodoQuery(tenantId, projectId, null, null, null, null,
                null, null, null, null, null, limit);
    }
}
