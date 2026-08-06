package de.mhus.vance.foot.tools.pack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Remembers which project-local tool packs the user approved
 * permanently. Lives in {@code <global .vancetope>/trusted-packs.yaml}
 * and <b>only</b> there: the whole point of the consent gate is that a
 * working directory cannot authorise its own packs, so the record of
 * that authorisation must sit somewhere the repository can't write.
 *
 * <pre>
 * trustedPacks:
 *   /Users/me/src/my-repo:
 *     - name: chrome
 *       reach: npx -y chrome-devtools-mcp@latest
 * </pre>
 *
 * <p>An entry matches on {@code name} <em>and</em> {@code reach} (the
 * spawned command / endpoint). Approving a pack therefore approves one
 * concrete shape — if the repo later swaps the command, the entry no
 * longer matches and the user is asked again.
 *
 * <p>Absent file = nothing trusted. A broken file is treated the same
 * way (warn + nothing trusted) rather than failing the run: losing the
 * remembered answers costs one extra prompt, whereas refusing to start
 * would be a worse failure mode for a file the user may have hand-edited.
 */
@Component
@Slf4j
public class TrustedPacksStore {

    public static final String FILE_NAME = "trusted-packs.yaml";

    private final YAMLMapper mapper = (YAMLMapper) YAMLMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /** File-bound root: project directory → approved packs. */
    @Data
    public static class Document {
        private Map<String, List<Entry>> trustedPacks = new LinkedHashMap<>();
    }

    /** One approved pack shape. */
    @Data
    public static class Entry {
        private @Nullable String name;
        private @Nullable String reach;
    }

    public Path file(Path globalHomeDir) {
        return globalHomeDir.resolve(FILE_NAME);
    }

    /** Whether {@code pack} was permanently approved for {@code projectDir}. */
    public boolean isTrusted(Path globalHomeDir, Path projectDir, LoadedPack pack) {
        Document doc = load(globalHomeDir);
        List<Entry> entries = doc.getTrustedPacks().get(key(projectDir));
        if (entries == null) return false;
        String reach = pack.reachDescription();
        for (Entry e : entries) {
            if (pack.name().equals(e.getName()) && reach.equals(e.getReach())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Records {@code pack} as permanently approved for {@code projectDir}.
     * Idempotent — an identical entry is not duplicated.
     */
    public void trust(Path globalHomeDir, Path projectDir, LoadedPack pack) {
        Document doc = load(globalHomeDir);
        List<Entry> entries = doc.getTrustedPacks()
                .computeIfAbsent(key(projectDir), k -> new ArrayList<>());
        String reach = pack.reachDescription();
        for (Entry e : entries) {
            if (pack.name().equals(e.getName()) && reach.equals(e.getReach())) {
                return;
            }
        }
        Entry entry = new Entry();
        entry.setName(pack.name());
        entry.setReach(reach);
        entries.add(entry);
        save(globalHomeDir, doc);
    }

    /** Reads the document; absent or broken file → empty document. */
    public Document load(Path globalHomeDir) {
        Path file = file(globalHomeDir);
        if (!Files.isRegularFile(file)) {
            return new Document();
        }
        try {
            Document doc = mapper.readValue(file.toFile(), Document.class);
            if (doc == null) return new Document();
            if (doc.getTrustedPacks() == null) doc.setTrustedPacks(new LinkedHashMap<>());
            return doc;
        } catch (Exception e) {
            log.warn("TrustedPacksStore: cannot read {} ({}) — treating every project pack "
                    + "as untrusted", file, e.getMessage());
            return new Document();
        }
    }

    private void save(Path globalHomeDir, Document doc) {
        Path file = file(globalHomeDir);
        try {
            Files.createDirectories(globalHomeDir);
            mapper.writeValue(file.toFile(), doc);
            log.debug("TrustedPacksStore: wrote {}", file);
        } catch (IOException | RuntimeException e) {
            // A failed write costs a prompt next start; it must not take
            // down the pack that was just approved for this run.
            log.warn("TrustedPacksStore: cannot write {} ({}) — the approval applies to "
                    + "this run only", file, e.getMessage());
        }
    }

    /** Absolute, normalised project directory — the map key. */
    private static String key(Path projectDir) {
        return projectDir.toAbsolutePath().normalize().toString();
    }
}
