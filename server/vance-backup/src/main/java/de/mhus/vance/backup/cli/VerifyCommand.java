package de.mhus.vance.backup.cli;

import de.mhus.vance.backup.config.BackupConfig;
import de.mhus.vance.backup.config.ConfigException;
import de.mhus.vance.backup.config.ConfigLoader;
import de.mhus.vance.backup.manifest.ManifestEntry;
import de.mhus.vance.backup.manifest.ManifestIO;
import de.mhus.vance.backup.snapshot.SnapshotWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Subcommand {@code vancetope-backup verify} — re-hashes every file in
 * a snapshot and compares against the SHA-256 in {@code entries.jsonl}.
 *
 * <p>What this catches:
 * <ul>
 *   <li>Bit rot on disk (the original file changed without the
 *       snapshot knowing).
 *   <li>A failed copy / hardlink that produced a zero-byte or
 *       truncated file (would land as FAILED in the manifest already,
 *       but verify catches the case where the entry was logged with
 *       DOWNLOADED but the bytes on disk are wrong).
 *   <li>Files deleted by hand.
 * </ul>
 *
 * <p>What this does NOT catch: an entry whose SHA-256 was wrong at
 * download time. {@code run} hashes the bytes after writing, so an
 * entry's SHA-256 is the on-disk hash — a hash collision here would
 * be news in its own right.
 */
@Command(
        name = "verify",
        mixinStandardHelpOptions = true,
        description = "Re-hash files in a snapshot and compare to the manifest.")
@NullMarked
public final class VerifyCommand implements Callable<Integer> {

    @Option(
            names = {"--config"},
            description = "Override the config file path (default: ~/.vancetope/backup/backup.yaml).")
    private @Nullable Path config;

    @Option(
            names = {"--snapshot"},
            description = "Snapshot id to verify (default: the LATEST one).")
    private @Nullable String snapshotId;

    private final PrintWriter out = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
    private final PrintWriter err = new PrintWriter(System.err, true, StandardCharsets.UTF_8);

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
        Path snapshot = resolveSnapshot(target, snapshotId);
        if (snapshot == null) {
            err.println("Snapshot not found: " + (snapshotId != null ? snapshotId : "LATEST"));
            return 4;
        }

        ManifestIO io = new ManifestIO();
        java.util.List<ManifestEntry> entries;
        try {
            entries = io.readEntries(snapshot);
        } catch (IOException e) {
            err.println("Cannot read entries: " + e.getMessage());
            return 2;
        }

        long ok = 0;
        long failed = 0;
        long missing = 0;
        long skipped = 0;

        for (ManifestEntry entry : entries) {
            if (entry.stored() == ManifestEntry.Stored.FAILED) {
                skipped++;
                continue;
            }
            if (entry.file() == null || entry.sha256() == null) {
                skipped++;
                continue;
            }
            Path file = snapshot.resolve(entry.file());
            if (!Files.exists(file)) {
                err.println("MISSING: " + entry.project() + "/" + entry.path());
                missing++;
                continue;
            }
            try {
                String actual = sha256OfFile(file);
                if (actual.equals(entry.sha256())) {
                    ok++;
                } else {
                    err.println("MISMATCH: " + entry.project() + "/" + entry.path()
                            + " expected=" + entry.sha256().substring(0, 12)
                            + " actual=" + actual.substring(0, 12));
                    failed++;
                }
            } catch (IOException e) {
                err.println("ERROR reading " + entry.project() + "/" + entry.path() + ": " + e.getMessage());
                failed++;
            }
        }

        out.println();
        out.println("Snapshot : " + snapshot.getFileName());
        out.println("OK       : " + ok);
        out.println("Failed   : " + failed);
        out.println("Missing  : " + missing);
        out.println("Skipped  : " + skipped);

        return (failed == 0 && missing == 0) ? 0 : 1;
    }

    private static @Nullable Path resolveSnapshot(Path target, @Nullable String id) {
        if (id == null) {
            return SnapshotWriter.latestSnapshot(target);
        }
        Path dir = target.resolve(id);
        return Files.exists(dir) ? dir : null;
    }

    private static String sha256OfFile(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                digest.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available", e);
        }
    }

    /** Suppress unused warning on @Option import — kept for future flags. */
    @SuppressWarnings("unused")
    private static Optional<String> noopForImportAnchor() {
        return Optional.empty();
    }
}
