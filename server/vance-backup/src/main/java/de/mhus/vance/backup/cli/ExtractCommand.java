package de.mhus.vance.backup.cli;

import de.mhus.vance.backup.config.BackupConfig;
import de.mhus.vance.backup.config.ConfigException;
import de.mhus.vance.backup.config.ConfigLoader;
import de.mhus.vance.backup.manifest.ManifestEntry;
import de.mhus.vance.backup.manifest.ManifestIO;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Subcommand {@code vancetope-backup extract <project>/<path>}.
 *
 * <p>Copies a file out of a snapshot to a target directory. By
 * default, the newest available version is used; pass
 * {@code --at <snapshot-id>} to pick a specific snapshot.
 *
 * <p>The output filename is the document's {@code title} if
 * available, otherwise its path. The output is suffixed with a
 * timestamp so re-running the command never overwrites a previous
 * extract. {@code --force} disables the suffix.
 *
 * <p>This is manifest-only + filesystem — no server calls.
 */
@Command(
        name = "extract",
        mixinStandardHelpOptions = true,
        description = "Copy a document out of a snapshot to a directory.")
@NullMarked
public final class ExtractCommand implements Callable<Integer> {

    @Option(
            names = {"--config"},
            description = "Override the config file path (default: ~/.vancetope/backup/backup.yaml).")
    private @Nullable Path config;

    @Option(
            names = {"--at"},
            description = "Snapshot id to extract from (default: the newest that has the document).")
    private @Nullable String atSnapshot;

    @Option(
            names = {"--to"},
            description = "Output directory (default: current working directory).")
    private @Nullable String toDir;

    @Option(
            names = {"--force"},
            description = "Overwrite the output file if it already exists.")
    private boolean force;

    @picocli.CommandLine.Parameters(arity = "1", description = "Document reference: <project>/<path>")
    private @Nullable String ref;

    private final PrintWriter out = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
    private final PrintWriter err = new PrintWriter(System.err, true, StandardCharsets.UTF_8);
    private static final System.Logger LOG = System.getLogger(ExtractCommand.class.getName());

    @Override
    public Integer call() {
        if (ref == null || !ref.contains("/")) {
            err.println("Usage: extract <project>/<path> [--at <snapshot>] [--to <dir>]");
            return 4;
        }
        String project = ref.substring(0, ref.indexOf('/'));
        String path = ref.substring(ref.indexOf('/') + 1);

        BackupConfig cfg;
        try {
            cfg = new ConfigLoader().load(config);
        } catch (ConfigException e) {
            err.println("Configuration error: " + e.getMessage());
            return 4;
        }
        Path target = Path.of(cfg.target);
        if (!Files.exists(target)) {
            err.println("No snapshots yet — target " + target + " does not exist.");
            return 2;
        }

        // Find the snapshot directory.
        Path snapshotDir;
        if (atSnapshot != null) {
            snapshotDir = target.resolve(atSnapshot);
            if (!Files.exists(snapshotDir)) {
                err.println("Snapshot not found: " + atSnapshot);
                return 4;
            }
        } else {
            try (var stream = Files.list(target)) {
                snapshotDir = stream.filter(Files::isDirectory)
                        .filter(p -> !p.getFileName().toString().startsWith("."))
                        .max(Comparator.comparing((Path p) -> p.getFileName().toString()))
                        .orElse(null);
            } catch (IOException e) {
                err.println("Cannot list snapshots: " + e.getMessage());
                return 2;
            }
            if (snapshotDir == null) {
                err.println("No snapshots to extract from.");
                return 2;
            }
        }

        // Find the entry in the manifest.
        ManifestEntry entry;
        try {
            List<ManifestEntry> entries = new ManifestIO().readEntries(snapshotDir);
            entry = entries.stream()
                    .filter(e -> project.equals(e.project()) && path.equals(e.path()))
                    .filter(e -> e.stored() != ManifestEntry.Stored.FAILED)
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            err.println("Cannot read manifest: " + e.getMessage());
            return 2;
        }
        if (entry == null) {
            err.println("Document not found in snapshot " + snapshotDir.getFileName() + ".");
            return 2;
        }

        Path source = snapshotDir.resolve(entry.file());
        if (!Files.exists(source)) {
            err.println("File missing on disk: " + source);
            return 2;
        }

        Path outputDir = toDir != null ? Path.of(toDir) : Path.of(".");
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            err.println("Cannot create output dir: " + e.getMessage());
            return 2;
        }

        String baseName = entry.title() != null && !entry.title().isBlank() ? entry.title() : path.replace('/', '_');
        if (baseName.contains("/")) {
            baseName = baseName.replace('/', '_');
        }
        Path output = outputDir.resolve(baseName);
        if (!force && Files.exists(output)) {
            // Suffix with snapshot id so re-runs don't overwrite.
            String suffix = "@" + snapshotDir.getFileName();
            output = outputDir.resolve(baseName + suffix);
            if (Files.exists(output)) {
                err.println("Output already exists: " + output + " (use --force to overwrite)");
                return 2;
            }
        }
        try {
            Files.copy(
                    source, output, force ? StandardCopyOption.REPLACE_EXISTING : StandardCopyOption.COPY_ATTRIBUTES);
        } catch (IOException e) {
            err.println("Copy failed: " + e.getMessage());
            return 2;
        }
        out.println("Extracted to " + output);
        return 0;
    }
}
