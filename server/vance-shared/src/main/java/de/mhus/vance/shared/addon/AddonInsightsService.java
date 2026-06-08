package de.mhus.vance.shared.addon;

import de.mhus.vance.api.addon.AddonInsightDto;
import de.mhus.vance.api.addon.ChecksumStatus;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/**
 * Read-only aggregator backing the Insights / Addons tab. Joins three
 * sources of truth into one row per addon:
 *
 * <ul>
 *   <li>{@code db.addons} (via {@link AddonService}) — admin intent
 *       (enabled flag, source path, checksum).</li>
 *   <li>{@code /shared/addons/<id>/<version>/.ready} — the entrypoint
 *       successfully unpacked the bundle (the unpack truth).</li>
 *   <li>{@code VanceAddon} Spring beans — Spring actually registered
 *       the addon's autoconfiguration (the runtime truth).</li>
 * </ul>
 *
 * <p>The diagnostic value lives in the divergence between the latter
 * two: a bundle that is {@code unpacked} but has no registered bean
 * means Spring rejected the AutoConfiguration. Conversely, a bean
 * without an unpacked directory means the addon is shipped directly
 * with the brain image (unusual but valid).
 */
@Service
@EnableConfigurationProperties(AddonHomeProperties.class)
@Slf4j
public class AddonInsightsService {

    private final AddonService addonService;
    private final AddonHomeProperties props;
    private final Map<String, VanceAddon> addonBeans;

    public AddonInsightsService(
            AddonService addonService,
            AddonHomeProperties props,
            List<VanceAddon> beans) {
        this.addonService = addonService;
        this.props = props;
        // Index by the addon's self-reported id, not the bean name.
        // Multiple beans claiming the same id are a misconfiguration —
        // we keep the first and log a warning.
        this.addonBeans = beans.stream()
                .collect(Collectors.toMap(
                        VanceAddon::id,
                        v -> v,
                        (a, b) -> {
                            log.warn("AddonInsightsService: duplicate VanceAddon id '{}' — "
                                    + "keeping {} and ignoring {}",
                                    a.id(), a.getClass().getName(), b.getClass().getName());
                            return a;
                        }));
    }

    /**
     * One {@link AddonInsightDto} per addon known to the system —
     * union of {@code db.addons} rows and registered
     * {@link VanceAddon} beans. Beans without a DB row appear as
     * "floating" entries (typical in the IDE / vance-brain-all dev
     * run where no entrypoint seeded the rows). DB rows without a
     * bean appear as "broken" or "missing" depending on the on-disk
     * state. Stable order: ascending by name.
     */
    public List<AddonInsightDto> listForInsights() {
        Path home = Path.of(props.getHome());
        Path cache = Path.of(props.getCache());

        Map<String, AddonDocument> docsByName = addonService.listAll().stream()
                .collect(Collectors.toMap(AddonDocument::getName, d -> d, (a, b) -> a));

        // Union of every name we've seen anywhere — DB or bean.
        java.util.Set<String> names = new java.util.TreeSet<>();
        names.addAll(docsByName.keySet());
        names.addAll(addonBeans.keySet());

        List<AddonInsightDto> out = new ArrayList<>(names.size());
        for (String name : names) {
            AddonDocument doc = docsByName.get(name);
            VanceAddon bean = addonBeans.get(name);
            out.add(toInsight(name, doc, bean, home, cache));
        }
        return out;
    }

    /**
     * Build a row. Exactly one of {@code doc} / {@code bean} may be
     * null, but never both — the caller comes from a union of both
     * sources.
     */
    private AddonInsightDto toInsight(
            String name,
            @Nullable AddonDocument doc,
            @Nullable VanceAddon bean,
            Path home, Path cache) {
        DiskState disk = inspectDisk(name, home);
        ChecksumStatus cs = doc == null ? ChecksumStatus.NONE : verifyChecksum(doc, cache);

        boolean unpacked = disk.ready;
        boolean beanRegistered = bean != null;
        // If there is no DB row, treat the bean's presence as
        // "intended on" — the brain is dev-mode and the entrypoint
        // simply didn't seed the row. Loaded reflects Spring truth.
        boolean enabled = doc == null ? beanRegistered : doc.isEnabled();
        boolean loaded = beanRegistered && enabled;
        String displayName = bean != null
                ? bean.displayName()
                : (doc != null ? doc.getName() : name);
        String status = computeStatus(doc, enabled, unpacked, bean);

        return AddonInsightDto.builder()
                .name(name)
                .displayName(displayName)
                .path(doc != null ? doc.getPath() : "builtin:" + name)
                .enabled(enabled)
                .loaded(loaded)
                .unpacked(unpacked)
                .beanRegistered(beanRegistered)
                .version(disk.version)
                .checksum(doc != null ? doc.getChecksum() : null)
                .checksumStatus(cs)
                .createdAt(doc != null ? doc.getCreatedAt() : null)
                .unpackedAt(disk.unpackedAt)
                .status(status)
                .build();
    }

    /**
     * Compute the diagnostic status text. Priority:
     * <ol>
     *   <li>Addon's own {@code status()} (when bean exists) — wins.</li>
     *   <li>Edge cases that diverge from the expected deployment
     *       (disabled-but-active / unpacked-but-no-bean).</li>
     * </ol>
     *
     * <p>Built-in addons (bean registered, no DB row) are <em>not</em>
     * a divergence — they ship inside the brain image and are
     * identified via the {@code path="builtin:…"} marker the
     * aggregator sets. Status stays {@code null} unless the addon
     * itself has something to report.
     */
    private static @Nullable String computeStatus(
            @Nullable AddonDocument doc,
            boolean enabled,
            boolean unpacked,
            @Nullable VanceAddon bean) {
        if (bean != null) {
            String own = bean.status();
            if (own != null && !own.isBlank()) {
                return own;
            }
            if (doc != null && !enabled) {
                return "disabled in db.addons but bean is registered";
            }
            return null;
        }
        // No bean.
        if (!enabled) {
            return null;     // Silent — admin disabled it, no bean is expected.
        }
        if (unpacked) {
            return "unpacked but no VanceAddon bean registered — "
                    + "check META-INF/spring/AutoConfiguration.imports";
        }
        return "not deployed (no on-disk bundle, no Spring bean)";
    }

    /**
     * Scan {@code <home>/<name>/} for the highest-versioned subdir
     * that has a {@code .ready} marker. Returns blank state when the
     * home dir doesn't exist or the addon hasn't been unpacked there.
     */
    private DiskState inspectDisk(String name, Path home) {
        Path addonRoot = home.resolve(name);
        if (!Files.isDirectory(addonRoot)) {
            return DiskState.EMPTY;
        }
        Optional<Path> best;
        try (Stream<Path> versions = Files.list(addonRoot)) {
            best = versions
                    .filter(Files::isDirectory)
                    .filter(p -> Files.exists(p.resolve(".ready")))
                    .max(Comparator.comparing(p -> p.getFileName().toString()));
        } catch (IOException e) {
            log.warn("AddonInsightsService: cannot list versions for {}: {}", name, e.getMessage());
            return DiskState.EMPTY;
        }
        if (best.isEmpty()) return DiskState.EMPTY;

        Path versionDir = best.get();
        Path manifest = versionDir.resolve("META-INF").resolve("vance-addon.yaml");
        String version = parseManifestVersion(manifest);
        Instant unpackedAt = readyMtime(versionDir.resolve(".ready"));
        return new DiskState(true, version, unpackedAt);
    }

    /**
     * Verify the configured checksum (if any) against the cached
     * source bundle. Bundled addons (path starts with {@code bundled:})
     * never have a cache file to verify against and always return
     * {@link ChecksumStatus#NONE} or {@link ChecksumStatus#UNVERIFIED}.
     */
    private ChecksumStatus verifyChecksum(AddonDocument doc, Path cache) {
        String expected = doc.getChecksum();
        if (expected == null || expected.isBlank()) return ChecksumStatus.NONE;

        Path vabFile = cache.resolve(doc.getName() + ".vab");
        if (!Files.isRegularFile(vabFile)) return ChecksumStatus.UNVERIFIED;

        String actual = sha256(vabFile);
        if (actual == null) return ChecksumStatus.UNVERIFIED;

        return actual.equalsIgnoreCase(expected) ? ChecksumStatus.VERIFIED : ChecksumStatus.MISMATCH;
    }

    /**
     * Parse {@code version:} from a Spring-style YAML manifest.
     * Intentionally lightweight (no SnakeYAML) — the manifest has at
     * most a handful of top-level scalar keys and the boot path
     * already parses it this way in shell.
     */
    static @Nullable String parseManifestVersion(Path manifest) {
        if (!Files.isRegularFile(manifest)) return null;
        try {
            for (String raw : Files.readAllLines(manifest)) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                String key = line.substring(0, colon).strip();
                if (!key.equals("version")) continue;
                String value = line.substring(colon + 1).strip();
                if ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                return value.isEmpty() ? null : value;
            }
        } catch (IOException e) {
            log.warn("AddonInsightsService: cannot read manifest {}: {}", manifest, e.getMessage());
        }
        return null;
    }

    private static @Nullable Instant readyMtime(Path readyMarker) {
        try {
            return Files.getLastModifiedTime(readyMarker).toInstant();
        } catch (IOException e) {
            return null;
        }
    }

    private static @Nullable String sha256(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return "sha256:" + HexFormat.of().formatHex(md.digest()).toLowerCase(Locale.ROOT);
        } catch (IOException | NoSuchAlgorithmException e) {
            log.warn("AddonInsightsService: cannot hash {}: {}", file, e.getMessage());
            return null;
        }
    }

    private record DiskState(boolean ready, @Nullable String version, @Nullable Instant unpackedAt) {
        static final DiskState EMPTY = new DiskState(false, null, null);
    }
}
