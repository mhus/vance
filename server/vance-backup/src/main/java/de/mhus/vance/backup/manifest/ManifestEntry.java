package de.mhus.vance.backup.manifest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/**
 * One row in {@code entries.jsonl}
 * ({@code planning/user-backup.md} §4.6).
 *
 * <p>Server fields come straight from the {@code BackupEntryDto}
 * returned by {@code POST /backup/projects/{project}/entries}.
 * Client fields ({@link #file}, {@link #sha256}, {@link #stored})
 * are filled in by the snapshot writer and tell a future tool where
 * the file landed, what its content hash is, and how it got there
 * (downloaded, hardlinked, copied, or failed).
 *
 * <p>Encrypted documents carry the same {@code stored:"downloaded"}
 * marker — the ciphertext was downloaded, decryption is a separate
 * concern that happens outside this tool
 * ({@code planning/user-backup.md} §6.4).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ManifestEntry(
        /** Project name as the brain returned it. */
        @JsonProperty("project") String project,
        /** Document path inside the project (slash-separated). */
        @JsonProperty("path") String path,
        /** Path on disk relative to the snapshot root. */
        @JsonProperty("file") String file,
        @JsonProperty("id") @Nullable String id,
        @JsonProperty("lineageId") @Nullable String lineageId,
        @JsonProperty("kind") @Nullable String kind,
        @JsonProperty("title") @Nullable String title,
        @JsonProperty("mimeType") @Nullable String mimeType,
        @JsonProperty("size") long size,
        @JsonProperty("createdAtMs") @Nullable Long createdAtMs,
        @JsonProperty("updatedAtMs") @Nullable Long updatedAtMs,
        /**
         * Content version at the time of the run — the same value
         * {@code storageId} on the server (or {@code id} for inline
         * / empty documents). Used for ETag-based deduplication on
         * the next run.
         */
        @JsonProperty("contentVersion") @Nullable String contentVersion,
        @JsonProperty("encrypted") boolean encrypted,
        /**
         * SHA-256 of the downloaded bytes, hex-encoded (no prefix).
         * Null only when {@link #stored} is {@link Stored#FAILED} —
         * a failed download has no bytes to hash.
         */
        @JsonProperty("sha256") @Nullable String sha256,
        @JsonProperty("stored") Stored stored) {

    public enum Stored {
        /** Newly downloaded in this run. */
        DOWNLOADED,
        /** Hardlinked from the previous snapshot (cheap). */
        LINKED,
        /** Copied because hardlink was not possible (different filesystem, link budget exceeded). */
        COPIED,
        /** Download or write failed; entry recorded, no file on disk. */
        FAILED
    }
}
