package de.mhus.vance.backup.cli;

import de.mhus.vance.api.backup.BackupEntriesRequest;
import de.mhus.vance.api.backup.BackupEntriesResponse;
import de.mhus.vance.api.backup.BackupEntryDto;
import de.mhus.vance.api.backup.BackupProjectDto;
import de.mhus.vance.api.backup.BackupProjectsRequest;
import de.mhus.vance.api.backup.BackupProjectsResponse;
import de.mhus.vance.api.backup.BackupRunsRequest;
import de.mhus.vance.api.backup.BackupRunsResponse;
import de.mhus.vance.api.util.PathGlob;
import de.mhus.vance.backup.api.ApiException;
import de.mhus.vance.backup.api.BackupApiClient;
import de.mhus.vance.backup.config.BackupConfig;
import de.mhus.vance.backup.config.ConfigException;
import de.mhus.vance.backup.config.ConfigLoader;
import de.mhus.vance.backup.manifest.ManifestEntry;
import de.mhus.vance.backup.manifest.ManifestIO;
import de.mhus.vance.backup.manifest.SnapshotManifest;
import de.mhus.vance.backup.snapshot.SnapshotWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Subcommand {@code vancetope-backup run} — the actual backup.
 *
 * <p>Drives the seven-step flow from
 * {@code planning/user-backup.md} §4.7:
 *
 * <ol>
 *   <li>Open config + acquire {@code .lock} on the target.
 *   <li>{@code GET /backup/info} — policy + token check.
 *   <li>{@code POST /backup/projects} with the filter.
 *   <li>Per project: {@code POST /backup/projects/{p}/entries} paginated.
 *   <li>Per entry (after the client-side deny filter): if the previous
 *       snapshot has the same {@code contentVersion}, hardlink or copy
 *       without contacting the server; otherwise stream the download.
 *   <li>Finish the snapshot (atomic publish, LATEST marker).
 *   <li>{@code POST /backup/runs} — extends the sliding-lifetime token.
 * </ol>
 *
 * <p>Per-file failures are recorded with {@code stored:"failed"} in
 * {@code entries.jsonl} and counted in the manifest. The run exits
 * with code 1 when partial failures occurred; 2 on auth/policy
 * errors; 3 on target not writable.
 */
@Command(name = "run", mixinStandardHelpOptions = true, description = "Create a snapshot of all readable documents.")
@NullMarked
public final class RunCommand implements Callable<Integer> {

    @Option(
            names = {"--config"},
            description = "Override the config file path (default: ~/.vancetope/backup/backup.yaml).")
    private @Nullable Path config;

    private final PrintWriter out = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
    private final PrintWriter err = new PrintWriter(System.err, true, StandardCharsets.UTF_8);
    private static final System.Logger LOG = System.getLogger(RunCommand.class.getName());

    /** Hardcoded tool version — surfaced in the manifest and the run summary. */
    public static final String TOOL_VERSION = "vancetope-backup 0.3.0-dev";

    /** Page size for the entries endpoint. */
    private static final int ENTRIES_PAGE_SIZE = 500;

    @Override
    public Integer call() {
        BackupConfig cfg;
        try {
            cfg = new ConfigLoader().load(config);
        } catch (ConfigException e) {
            err.println("Configuration error: " + e.getMessage());
            return 4;
        }

        Path target = Path.of(cfg.target);
        Path lockFile = target.resolve(".lock");
        try {
            // Atomic lock: Files.createFile fails if the file exists.
            // The second run that races here gets a clear IOException.
            Files.createDirectories(target);
            Files.createFile(lockFile);
        } catch (IOException e) {
            err.println("Target not writable or already locked: " + target
                    + " — another run may be in progress, or the target directory cannot be created.");
            return 3;
        }

        @Nullable SnapshotWriter writer = null;
        boolean published = false;
        try (var client = new BackupApiClient(URI.create(cfg.server.url), cfg.server.tenant, cfg.token)) {

            // 1. Policy check
            var info = client.getInfo();
            if (!info.isAllowed()) {
                err.println("Server policy denies this token: " + info.getDenialReason());
                return 2;
            }

            // 2. Snapshot directory
            Instant startedAt = Instant.now();
            String snapshotId = new SnapshotWriter(target).newSnapshotId(startedAt);
            Path previousSnapshot = SnapshotWriter.latestSnapshot(target);
            writer = new SnapshotWriter(target);
            SnapshotManifest initial = new SnapshotManifest(
                    SnapshotManifest.CURRENT_FORMAT,
                    TOOL_VERSION,
                    cfg.server.url,
                    cfg.server.tenant,
                    null, // user unknown without an extra call — brain fills it
                    snapshotId,
                    startedAt.toString(),
                    null,
                    new SnapshotManifest.Filter(
                            new SnapshotManifest.ProjectsFilter(
                                    cfg.projects.include, cfg.projects.exclude, cfg.projects.includeDisabled),
                            cfg.deny,
                            cfg.projectDeny),
                    null,
                    new SnapshotManifest.Counts(0, 0, 0, 0, 0),
                    new SnapshotManifest.Bytes(0L, 0L));
            Path inProgress = writer.begin(snapshotId, initial, previousSnapshot);
            out.println("Snapshot started: " + inProgress);

            // 3. List projects
            BackupProjectsRequest projectsReq = new BackupProjectsRequest();
            projectsReq.setInclude(cfg.projects.include);
            projectsReq.setExclude(cfg.projects.exclude);
            projectsReq.setIncludeDisabled(cfg.projects.includeDisabled);
            BackupProjectsResponse projectsResp = client.listProjects(projectsReq);
            List<BackupProjectDto> projects = projectsResp.getProjects();
            out.println("Projects to back up: " + projects.size());

            // 4. Walk each project, paging through entries
            long countDocs = 0;
            long countDownloaded = 0;
            long countLinked = 0;
            long countCopied = 0;
            long countFailed = 0;
            List<PathGlob> globalDeny = PathGlob.compileAll(cfg.deny);

            for (BackupProjectDto project : projects) {
                String projectName = project.getName();
                @Nullable String cursor = null;
                List<String> projectDeny = cfg.projectDeny.getOrDefault(projectName, List.of());
                List<PathGlob> projectDenyGlobs = PathGlob.compileAll(projectDeny);

                while (true) {
                    // The server narrows the transfer; the client applies the
                    // same globs again so the config is honoured even against
                    // a server that filters less (planning §6.3).
                    BackupEntriesRequest entriesReq = new BackupEntriesRequest();
                    entriesReq.setDeny(cfg.deny);
                    entriesReq.setProjectDeny(projectDeny);
                    entriesReq.setSize(ENTRIES_PAGE_SIZE);
                    if (cursor != null && !cursor.isEmpty()) {
                        entriesReq.setCursor(cursor);
                    }
                    BackupEntriesResponse entriesResp = client.listEntries(projectName, entriesReq);

                    for (BackupEntryDto entry : entriesResp.getEntries()) {
                        if (PathGlob.anyMatch(globalDeny, entry.getPath())
                                || PathGlob.anyMatch(projectDenyGlobs, entry.getPath())) {
                            continue;
                        }
                        countDocs++;
                        switch (ingestOne(client, writer, entry)) {
                            case DOWNLOADED -> countDownloaded++;
                            case LINKED -> countLinked++;
                            case COPIED -> countCopied++;
                            case FAILED -> countFailed++;
                        }
                    }

                    cursor = entriesResp.getNextCursor();
                    if (cursor == null || cursor.isEmpty()) {
                        break;
                    }
                }
            }

            // 5. Finish — atomic publish + LATEST marker
            SnapshotManifest.Counts counts =
                    new SnapshotManifest.Counts(countDocs, countDownloaded, countLinked, countCopied, countFailed);
            long bytesDownloaded = writer.bytesDownloaded();
            SnapshotManifest.Bytes bytes = new SnapshotManifest.Bytes(0L, bytesDownloaded);
            String finishedAt = Instant.now().toString();
            writer.finish(counts, bytes, finishedAt);
            published = true;

            out.println("Snapshot finished: " + snapshotId);
            out.println("  documents  : " + countDocs);
            out.println("  downloaded : " + countDownloaded);
            out.println("  linked     : " + countLinked);
            out.println("  copied     : " + countCopied);
            out.println("  failed     : " + countFailed);
            out.println("  bytes      : " + bytesDownloaded);

            // 6. Tell the brain — extends the sliding-lifetime token
            BackupRunsRequest runsReq = new BackupRunsRequest();
            runsReq.setToolVersion(TOOL_VERSION);
            // A published snapshot is a successful run, even with single
            // failed documents (exit 1) — planning §4.7: only an aborted run
            // does not extend the token. A document deleted mid-run must not
            // cost the user their access.
            runsReq.setSucceeded(true);
            // documentsCount is an int on the wire — a tenant with
            // more than 2^31 documents is implausible for a personal
            // backup tool, but the truncation is logged just in case.
            int docCount = countDocs > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) countDocs;
            if (docCount == Integer.MAX_VALUE && countDocs > Integer.MAX_VALUE) {
                LOG.log(
                        System.Logger.Level.WARNING,
                        "documentsCount truncated from " + countDocs + " to Integer.MAX_VALUE");
            }
            runsReq.setDocumentsCount(docCount);
            runsReq.setBytesDownloaded(bytesDownloaded);
            runsReq.setBytesTotal(0L); // server-side total unknown to client
            runsReq.setSnapshotId(snapshotId);
            runsReq.setFinishedAtMs(Instant.now().toEpochMilli());
            try {
                BackupRunsResponse runsResp = client.reportRun(runsReq);
                if (runsResp.extended() && runsResp.tokenExpiresAtMs() != null) {
                    out.println("Token extended; new expiry: " + Instant.ofEpochMilli(runsResp.tokenExpiresAtMs()));
                }
            } catch (ApiException e) {
                // Token extension is best-effort. The snapshot is on
                // disk; the operator can extend manually if needed.
                err.println("Run summary upload failed (snapshot is safe): " + e.getMessage());
            }

            return countFailed == 0 ? 0 : 1;

        } catch (ApiException e) {
            err.println("Server error: " + e.getMessage());
            return 2;
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "Run failed", e);
            err.println("Run failed: " + e.getMessage());
            return 2;
        } finally {
            if (writer != null && !published) {
                // Do not leave a half-written .in-progress directory behind.
                writer.abort();
            }
            try {
                Files.deleteIfExists(lockFile);
            } catch (IOException ignored) {
                // best-effort cleanup
            }
        }
    }

    /**
     * Ingests a single entry. The writer reuses the previous snapshot's file
     * when the content version is unchanged and only then calls the server —
     * an unchanged document costs no transfer.
     *
     * <p>A failure of this one document (deleted between listing and fetch,
     * a 5xx, a broken stream) is recorded as FAILED and the run goes on.
     * Credential failures (401/403) end the run: every following call would
     * fail the same way.
     */
    private static ManifestEntry.Stored ingestOne(BackupApiClient client, SnapshotWriter writer, BackupEntryDto entry)
            throws IOException {
        try {
            return writer.ingest(
                            entry,
                            () -> client.downloadContent(entry.getId(), null).body())
                    .stored();
        } catch (ApiException e) {
            if (e.statusCode() == 401 || e.statusCode() == 403) {
                throw e;
            }
            LOG.log(
                    System.Logger.Level.WARNING,
                    "Failed to fetch " + entry.getProjectId() + "/" + entry.getPath() + ": " + e.getMessage());
            return writer.recordFailed(entry).stored();
        }
    }

    /**
     * Reads the previous snapshot's manifest, used by callers who
     * want to compute a filter hash. Exposed for tests.
     */
    public static Optional<SnapshotManifest> readPreviousManifest(Path targetRoot) {
        Path latest = SnapshotWriter.latestSnapshot(targetRoot);
        if (latest == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ManifestIO().readManifest(latest));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
