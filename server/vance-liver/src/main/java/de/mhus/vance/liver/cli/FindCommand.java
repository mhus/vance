package de.mhus.vance.liver.cli;

import de.mhus.vance.liver.config.BackupConfig;
import de.mhus.vance.liver.config.ConfigException;
import de.mhus.vance.liver.config.ConfigLoader;
import de.mhus.vance.liver.manifest.ManifestEntry;
import de.mhus.vance.liver.manifest.ManifestIO;
import de.mhus.vance.liver.manifest.SnapshotManifest;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Subcommand {@code vancetope-backup find <pattern>}.
 *
 * <p>Walks every snapshot under the target and prints entries whose
 * path OR title matches {@code <pattern>} (regex). Default search is
 * {@code --path}; pass {@code --title} to match titles instead, or
 * {@code --both} for either.
 *
 * <p>This is a manifest-only operation — no server calls, no file
 * reads. The result includes which snapshot each match came from.
 */
@Command(
        name = "find",
        mixinStandardHelpOptions = true,
        description = "Search across all snapshots for entries whose path or title matches a regex.")
@NullMarked
public final class FindCommand implements Callable<Integer> {

    @Option(
            names = {"--config"},
            description = "Override the config file path (default: ~/.vancetope/liver/backup.yaml).")
    private @Nullable Path config;

    @Option(
            names = {"--path"},
            description = "Match against the document path (default).")
    private boolean matchPath;

    @Option(
            names = {"--title"},
            description = "Match against the document title.")
    private boolean matchTitle;

    @Option(
            names = {"--both"},
            description = "Match either path or title.")
    private boolean matchBoth;

    @Option(
            names = {"--deleted"},
            description = "Show only documents that exist in older snapshots but not in the latest one.")
    private boolean deleted;

    @Option(
            names = {"--snapshot"},
            description = "Search only within the named snapshot (default: all snapshots).")
    private @Nullable String snapshotId;

    @picocli.CommandLine.Parameters(arity = "1", description = "Regex to match against path/title.")
    private @Nullable String pattern;

    private final PrintWriter out = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
    private final PrintWriter err = new PrintWriter(System.err, true, StandardCharsets.UTF_8);
    private static final System.Logger LOG = System.getLogger(FindCommand.class.getName());

    @Override
    public Integer call() {
        if (pattern == null) {
            err.println("Missing search pattern.");
            return 4;
        }
        BackupConfig cfg;
        try {
            cfg = new ConfigLoader().load(config);
        } catch (ConfigException e) {
            err.println("Configuration error: " + e.getMessage());
            return 4;
        }
        boolean usePath = matchBoth || (!matchTitle);
        boolean useTitle = matchBoth || matchTitle;

        Path target = Path.of(cfg.target);
        if (!Files.exists(target)) {
            out.println("No snapshots yet — target " + target + " does not exist.");
            return 0;
        }

        Pattern regex;
        try {
            regex = Pattern.compile(pattern);
        } catch (java.util.regex.PatternSyntaxException e) {
            err.println("Invalid regex: " + e.getMessage());
            return 4;
        }

        List<Path> snapshots = listSnapshots(target);
        if (snapshotId != null) {
            snapshots.removeIf(p -> !p.getFileName().toString().equals(snapshotId));
        }
        if (snapshots.isEmpty()) {
            out.println("No snapshots to search.");
            return 0;
        }

        long matches = 0;
        for (Path snap : snapshots) {
            try {
                for (ManifestEntry entry : new ManifestIO().readEntries(snap)) {
                    if (matchesEntry(regex, entry, usePath, useTitle)) {
                        out.println(formatHit(snap, entry));
                        matches++;
                    }
                }
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "Cannot read " + snap + ": " + e.getMessage());
            }
        }
        if (matches == 0) {
            out.println("No matches.");
        }
        return 0;
    }

    private static boolean matchesEntry(Pattern regex, ManifestEntry e, boolean path, boolean title) {
        if (path && e.path() != null && regex.matcher(e.path()).find()) {
            return true;
        }
        if (title && e.title() != null && regex.matcher(e.title()).find()) {
            return true;
        }
        return false;
    }

    private static String formatHit(Path snapshot, ManifestEntry e) {
        return snapshot.getFileName() + "\t"
                + e.project() + "/" + e.path()
                + (e.title() != null ? "\t" + e.title() : "");
    }

    private static List<Path> listSnapshots(Path target) {
        List<Path> result = new ArrayList<>();
        try (var stream = Files.list(target)) {
            stream.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString())
                            .reversed())
                    .forEach(result::add);
        } catch (IOException e) {
            // best-effort
        }
        return result;
    }

    /** Suppress unused warning on imports. */
    @SuppressWarnings("unused")
    private static @Nullable SnapshotManifest unused() {
        return null;
    }
}
