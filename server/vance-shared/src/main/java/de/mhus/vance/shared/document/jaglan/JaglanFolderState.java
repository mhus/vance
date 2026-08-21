package de.mhus.vance.shared.document.jaglan;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * "This mount folder was listed at time X" — the piece that makes an empty
 * mount folder distinguishable from one nobody has opened yet.
 *
 * <p>Without it the two states look identical: {@code extractFolders} derives
 * folders from the {@code path} field of existing rows, so a folder with no
 * shell rows yields nothing either way. Every listing of a genuinely empty
 * folder would then go remote again, forever.
 *
 * <p>Lives in Mongo rather than in a per-pod map because it describes the
 * freshness of shell rows, which are themselves shared: a second pod must not
 * re-list what the first one just listed. The <b>failure</b> memory next to it
 * ({@link #failedAt}) is the opposite kind of state and deliberately short —
 * see {@code JaglanShellService}, which keeps the per-mount outage memory in
 * RAM and uses this field only for the folder-scoped record.
 *
 * <p>{@code expiresAt} carries a TTL index so stale markers disappear on
 * their own. As with {@code OAuthStateDocument}, the application is the
 * authoritative checker — Mongo's TTL monitor runs about once a minute, so a
 * marker may still be readable after it expired and every read compares the
 * timestamp itself.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "jaglan_folder_state")
public class JaglanFolderState {

    /**
     * Deterministic id from {@code (tenant, project, mount, folder)} — see
     * {@link JaglanPaths#folderStateId}. Derived rather than generated for the
     * same reason the shell rows are: the marker has to be findable again
     * after it expired and was rewritten, without a lookup by four fields.
     */
    @Id
    private @Nullable String id;

    private String tenantId = "";

    private String projectId = "";

    /** The mount name, for diagnostics and for bulk eviction per mount. */
    @Indexed
    private String mount = "";

    /** Mount-relative folder path; empty string for the mount root. */
    private String folder = "";

    /** When the source last answered a listing for this folder. */
    private @Nullable Instant listedAt;

    /** How many entries that listing returned — {@code 0} is a real answer. */
    private int entryCount;

    /**
     * When a listing for this folder last failed. Kept alongside
     * {@code listedAt} rather than replacing it: a failed refresh must not
     * erase the knowledge that we did once see this folder, because an empty
     * listing reads as "the mount is empty" and would make a reader conclude
     * the file does not exist.
     */
    private @Nullable Instant failedAt;

    /** Short reason for the last failure, for the status line. */
    private @Nullable String failureMessage;

    @Indexed(expireAfterSeconds = 0)
    private @Nullable Instant expiresAt;

    /** {@code true} when the marker is still within its declared TTL. */
    public boolean isFresh(Instant now) {
        return listedAt != null && expiresAt != null && expiresAt.isAfter(now);
    }
}
