package de.mhus.vance.backup.manifest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Local-only manifest format for one snapshot
 * ({@code planning/user-backup.md} §4.6).
 *
 * <p>The manifest is the operator's recovery document — every
 * information needed to make sense of the snapshot folder lives
 * here. It is written by {@code vancetope-backup run} and read by
 * {@code list}, {@code verify}, {@code find}, {@code history},
 * {@code extract}, and any external tool that wants to use the
 * snapshot as a folder of files.
 *
 * <p>This is intentionally NOT a public API contract: the format
 * can grow fields without bumping {@link #format} as long as older
 * fields stay readable. The format counter only ticks on a
 * breaking change to the field set or to {@code entries.jsonl}.
 *
 * <p>The {@code tool} field carries the tool name and version, so a
 * snapshot made by an older version is identifiable. Snapshots
 * produced by other implementations (rsync, custom scripts) leave
 * the field absent — they're still readable.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SnapshotManifest(
        @JsonProperty("format") int format,
        @JsonProperty("tool") @Nullable String tool,
        @JsonProperty("server") @Nullable String server,
        @JsonProperty("tenant") @Nullable String tenant,
        @JsonProperty("user") @Nullable String user,
        @JsonProperty("snapshotId") String snapshotId,
        @JsonProperty("startedAt") String startedAt,
        @JsonProperty("finishedAt") @Nullable String finishedAt,
        @JsonProperty("filter") Filter filter,
        @JsonProperty("filterHash") @Nullable String filterHash,
        @JsonProperty("counts") Counts counts,
        @JsonProperty("bytes") Bytes bytes) {

    /** Current manifest format version. Bumped only on breaking changes. */
    public static final int CURRENT_FORMAT = 1;

    /** What filter was active when this snapshot was made (planning §4.6). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Filter(
            @JsonProperty("projects") @Nullable ProjectsFilter projects,
            @JsonProperty("deny") java.util.List<String> deny,
            @JsonProperty("projectDeny") Map<String, java.util.List<String>> projectDeny) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProjectsFilter(
            @JsonProperty("include") java.util.List<String> include,
            @JsonProperty("exclude") java.util.List<String> exclude,
            @JsonProperty("includeDisabled") @Nullable Boolean includeDisabled) {}

    /**
     * Per-class counters — distinct so a glance at the manifest tells
     * whether this run was cheap (mostly linked) or expensive
     * (mostly downloaded / copied).
     */
    public record Counts(
            @JsonProperty("documents") long documents,
            @JsonProperty("downloaded") long downloaded,
            @JsonProperty("linked") long linked,
            @JsonProperty("copied") long copied,
            @JsonProperty("failed") long failed) {}

    public record Bytes(
            @JsonProperty("total") long total,
            @JsonProperty("downloaded") long downloaded) {}
}
