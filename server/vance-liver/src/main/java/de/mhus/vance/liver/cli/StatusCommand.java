package de.mhus.vance.liver.cli;

import de.mhus.vance.api.backup.BackupInfoResponse;
import de.mhus.vance.liver.api.BackupApiClient;
import de.mhus.vance.liver.config.BackupConfig;
import de.mhus.vance.liver.config.ConfigException;
import de.mhus.vance.liver.config.ConfigLoader;
import de.mhus.vance.liver.manifest.ManifestIO;
import de.mhus.vance.liver.manifest.SnapshotManifest;
import de.mhus.vance.liver.snapshot.SnapshotWriter;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Subcommand {@code vancetope-backup status}.
 *
 * <p>One-line dashboard for the operator — server view (token alive,
 * policy open, sliding-lifetime ceiling) plus local view (last
 * snapshot id, when, document count, drift). Does not modify
 * anything on disk or at the server.
 *
 * <p>The server-side view can fail with 401 if the token has been
 * revoked or expired; the local view still works in that case, so
 * the operator can see "last good run was 30 days ago, server says
 * 401, time to mint a new token".
 */
@Command(
        name = "status",
        mixinStandardHelpOptions = true,
        description = "Show server status (token, policy) and last local snapshot.")
@NullMarked
public final class StatusCommand implements Callable<Integer> {

    @Option(
            names = {"--config"},
            description = "Override the config file path (default: ~/.vancetope/liver/backup.yaml).")
    private @Nullable Path config;

    private final PrintWriter out = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
    private final PrintWriter err = new PrintWriter(System.err, true, StandardCharsets.UTF_8);
    private static final System.Logger LOG = System.getLogger(StatusCommand.class.getName());

    @Override
    public Integer call() {
        BackupConfig cfg;
        try {
            cfg = new ConfigLoader().load(config);
        } catch (ConfigException e) {
            err.println("Configuration error: " + e.getMessage());
            return 4;
        }

        // Server-side check
        out.println("== Server ==");
        try (var client = new BackupApiClient(URI.create(cfg.server.url), cfg.server.tenant, cfg.token)) {
            BackupInfoResponse info = client.getInfo();
            out.println("  brain        : " + cfg.server.url);
            out.println("  tenant       : " + cfg.server.tenant);
            out.println("  server ver   : " + info.getServerVersion());
            out.println("  manifest fmt : " + info.getManifestFormat());
            out.println("  policy       : " + (info.isAllowed() ? "allowed" : "DENIED — " + info.getDenialReason()));
            out.println("  idleDays max : " + info.getIdleDaysCeiling());
        } catch (RuntimeException e) {
            // Don't fail the whole command on a server hiccup — the
            // local view is the fallback for "is the tool still useful?".
            LOG.log(System.Logger.Level.WARNING, "Server status failed", e);
            err.println("  server check failed: " + e.getMessage());
        }

        // Local view
        out.println();
        out.println("== Local ==");
        out.println("  target       : " + cfg.target);
        Path target = Path.of(cfg.target);
        if (!Files.exists(target)) {
            out.println("  target dir   : (does not exist — no snapshots yet)");
            return 0;
        }
        Path latest = SnapshotWriter.latestSnapshot(target);
        if (latest == null) {
            out.println("  snapshots    : (none)");
            return 0;
        }
        out.println("  latest       : " + latest.getFileName());
        Path manifestFile = latest.resolve("manifest.json");
        if (!Files.exists(manifestFile)) {
            out.println("  manifest     : missing");
            return 0;
        }
        try {
            SnapshotManifest m = new ManifestIO().readManifest(latest);
            if (m.startedAt() != null) {
                Instant started = Instant.parse(m.startedAt());
                Duration ago = Duration.between(started, Instant.now());
                out.println("  started      : " + m.startedAt() + " (" + humanDuration(ago) + " ago)");
            }
            if (m.finishedAt() != null) {
                out.println("  finished     : " + m.finishedAt());
            }
            out.println("  documents    : " + m.counts().documents()
                    + " (linked=" + m.counts().linked()
                    + ", downloaded=" + m.counts().downloaded()
                    + ", copied=" + m.counts().copied()
                    + ", failed=" + m.counts().failed() + ")");
            out.println("  bytes down   : " + m.bytes().downloaded());
        } catch (java.io.IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Cannot read local manifest", e);
            err.println("  manifest     : unreadable: " + e.getMessage());
        }
        return 0;
    }

    private static String humanDuration(Duration d) {
        long s = Math.abs(d.getSeconds());
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "m";
        if (s < 86400) return (s / 3600) + "h";
        return (s / 86400) + "d";
    }
}
