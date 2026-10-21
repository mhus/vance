package de.mhus.vance.api.backup;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One document in the response of
 * {@code POST /brain/{tenant}/backup/projects/{project}/entries}. The shape
 * the {@code vance-liver} client stores in {@code entries.jsonl} per snapshot
 * (planning/user-backup.md §4.6).
 *
 * <p>{@link #contentVersion} equals {@code storageId} for storage-backed
 * documents and {@code id} for inline/empty documents — the same fallback the
 * brain uses for its ETag, so a backup run sees the same identity the live
 * server uses for change detection.
 *
 * <p>{@link #stored} is set by the client at download time and never reaches
 * the server. It is included here so the same DTO can flow through the
 * manifest pipeline without a parallel shape.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("backup")
public class BackupEntryDto {

    private String id;

    private String projectId;

    /** Virtual path inside the project, e.g. {@code "notes/thesis/ch1.md"}. */
    private String path;

    /** Stable across renames and restores; how {@code vance-liver} joins history across runs. */
    private String lineageId;

    /** {@code kind:} front-matter value, {@code null} for non-markdown / no front-matter. */
    private @Nullable String kind;

    private @Nullable String title;

    private @Nullable String mimeType;

    private long size;

    private @Nullable Long createdAtMs;

    /** Wall-clock of the last meaningful change — drives the mtime of the local file copy. */
    private @Nullable Long updatedAtMs;

    /** Opaque equality token. Storage-backed: equals {@code storageId}. Inline/empty: equals {@code id}. */
    private String contentVersion;

    /** Whether the body is age-armored ciphertext. The client stores ciphertext as-is. */
    private boolean encrypted;

    /** Client-side: how the file was stored in the snapshot. Never set by the server. */
    private @Nullable Stored stored;

    /**
     * Tags the snapshot carries as part of the deny-list filter (not the
     * document's own tags). Client-only, used to reproduce the manifest's
     * filter shape so a re-scan knows what the original run saw.
     */
    private @Nullable List<String> snapshotDenyPatterns;
}
