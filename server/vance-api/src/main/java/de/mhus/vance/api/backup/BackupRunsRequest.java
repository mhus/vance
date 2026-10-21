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
 * Body for {@code POST /brain/{tenant}/backup/runs} — the report a
 * {@code vance-liver} run sends after a successful snapshot. The server
 * uses this for two things:
 *
 * <ol>
 *   <li>Surface it under "Apps mit Zugriff" as "last run" — a row the user
 *       can eyeball to spot theft or breakage without an extra dashboard.</li>
 *   <li>Extend a sliding-lifetime token by one idle window. A successful
 *       run is the only signal that counts: a tool that only polls
 *       {@code /status} would otherwise keep the token alive without ever
 *       actually backing up.</li>
 * </ol>
 *
 * <p>{@code succeeded: false} skips the lifetime extension but still
 * records the attempt — partial runs are useful telemetry too.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("backup")
public class BackupRunsRequest {

    /** Logical name of the tool (e.g. {@code "vancetope-backup 0.3.0"}). Echoed back in "Apps mit Zugriff". */
    private String toolVersion;

    /** Whether the run completed without errors worth flagging. */
    private boolean succeeded;

    /** Number of documents the run touched (sum across projects). */
    private int documentsCount;

    /** Bytes actually downloaded this run. */
    private long bytesDownloaded;

    /** Bytes total covered by the snapshot (sum of all file sizes, deduplicated). */
    private long bytesTotal;

    /** Snapshot id the run wrote, for cross-referencing with the client's manifest. */
    private @Nullable String snapshotId;

    /** Projects the run covered, by name. */
    private @Nullable List<String> projects;

    /** Wall-clock of the run, milliseconds since epoch. */
    private long finishedAtMs;

    /**
     * Optional client error message — recorded against the last-used row but
     * not surfaced as a hard failure if {@code succeeded} is also {@code true}.
     */
    private @Nullable String note;
}
