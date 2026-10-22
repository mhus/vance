package de.mhus.vance.liver.manifest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON read/write helpers for the manifest files
 * ({@code planning/user-backup.md} §4.6).
 *
 * <p>{@code manifest.json} is a single pretty-printed object.
 * {@code entries.jsonl} is one JSON object per line — line-delimited
 * so {@code grep}, {@code wc}, and streaming readers work without
 * parsing the whole file.
 */
public final class ManifestIO {

    private final ObjectMapper mapper;

    public ManifestIO() {
        // JSON Lines: no pretty-printing, no trailing newline between
        // records — each entry is on its own line. SnapshotManifest
        // is pretty-printed; the per-line variant below overrides.
        this.mapper = JsonMapper.builder().build();
    }

    /** Writes {@code manifest.json} to {@code snapshotDir/manifest.json}. */
    public void writeManifest(Path snapshotDir, SnapshotManifest manifest) throws IOException {
        Files.createDirectories(snapshotDir);
        Path file = snapshotDir.resolve("manifest.json");
        try (var out = Files.newBufferedWriter(file)) {
            out.write(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest));
        }
    }

    /** Reads {@code manifest.json} from a snapshot directory. */
    public SnapshotManifest readManifest(Path snapshotDir) throws IOException {
        Path file = snapshotDir.resolve("manifest.json");
        if (!Files.exists(file)) {
            throw new IOException("manifest.json not found in " + snapshotDir);
        }
        return mapper.readValue(file.toFile(), SnapshotManifest.class);
    }

    /** Appends one entry to {@code entries.jsonl}. Atomic per-line via a writer per line. */
    public void appendEntry(Path snapshotDir, ManifestEntry entry) throws IOException {
        Files.createDirectories(snapshotDir);
        Path file = snapshotDir.resolve("entries.jsonl");
        // Append mode with auto-flush — a crash mid-line loses at
        // most one record. The manifest's counts tell the operator
        // whether something went wrong.
        try (var out = Files.newBufferedWriter(
                file, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)) {
            out.write(mapper.writeValueAsString(entry));
            out.newLine();
        }
    }

    /**
     * Reads {@code entries.jsonl} into a list. Use {@link
     * #streamEntries} instead when the file may be large.
     */
    public List<ManifestEntry> readEntries(Path snapshotDir) throws IOException {
        Path file = snapshotDir.resolve("entries.jsonl");
        if (!Files.exists(file)) {
            return List.of();
        }
        List<ManifestEntry> result = new ArrayList<>();
        try (var reader = Files.newBufferedReader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                result.add(mapper.readValue(line, ManifestEntry.class));
            }
        }
        return result;
    }

    /** Test-friendly mapper accessor. */
    ObjectMapper mapper() {
        return mapper;
    }
}
