package de.mhus.vance.api.mount;

import java.time.Duration;

import de.mhus.vance.api.documents.MountAccess;
import org.jspecify.annotations.Nullable;

/**
 * A configured mount, as reported by the port's {@code mounts(...)} —
 * the answer to "does this project have any mounted source at all", which
 * decides whether {@code _ext} is shown in a folder listing.
 *
 * <p>Everything here must be answerable <b>without touching the source</b>.
 * {@code mounts(...)} sits on the hot path of three listing surfaces, so it
 * resolves configuration and reads whatever the capabilities cache already
 * holds; a cache miss reports {@code null}/{@code UNKNOWN} instead of
 * blocking a folder listing on a remote call. The number fills in on the
 * next listing once the cache is warm.
 *
 * @param name        the mount name — the path segment in
 *                    {@code _ext/<name>/...}, and therefore identity: it is
 *                    part of the derived document id, so renaming is not
 *                    supported (create new, remove old)
 * @param displayName label for configuration UI and logs; falls back to
 *                    {@code name}
 * @param protocolId  which protocol serves this mount, for diagnostics
 * @param access      what the source allows, {@code UNKNOWN} when the
 *                    capabilities are not cached yet or it is unreachable
 * @param itemCount   the source's own declaration of how much it holds, used
 *                    as the folder count for {@code _ext/<name>} when no
 *                    fresh listing exists. {@code null} means unknown — a
 *                    source that cannot count says nothing rather than 0,
 *                    because 0 reads as "empty folder" in a file tree.
 * @param statusText  one line for the configuration/insights view, e.g. why
 *                    the source is not answering. {@code null} when fine.
 * @param metadataTtl how long a metadata shell row and a folder listing for
 *                    this mount stay valid — the source's own declaration,
 *                    already clamped to the floor by
 *                    {@code JaglanCapabilities}. Travels here rather than on
 *                    each entry because it is per-mount policy, not a
 *                    property of a single file.
 */
public record MountedSource(
        String name,
        @Nullable String displayName,
        String protocolId,
        MountAccess access,
        @Nullable Long itemCount,
        @Nullable String statusText,
        Duration metadataTtl) {

    /** Applied when a caller builds a source without stating a TTL. */
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

    public MountedSource {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        name = name.strip();
        if (protocolId == null) protocolId = "";
        if (access == null) access = MountAccess.UNKNOWN;
        if (itemCount != null && itemCount < 0) itemCount = null;
        if (metadataTtl == null || metadataTtl.isNegative() || metadataTtl.isZero()) {
            // Zero is already impossible coming from JaglanCapabilities, which
            // clamps a "do not cache" declaration to its floor. Folding it
            // here too keeps a hand-built source from producing a row that
            // expires the instant it is written.
            metadataTtl = DEFAULT_TTL;
        }
    }

    /** Label for UI — {@code displayName} when set, else the mount name. */
    public String label() {
        return displayName == null || displayName.isBlank() ? name : displayName;
    }
}
