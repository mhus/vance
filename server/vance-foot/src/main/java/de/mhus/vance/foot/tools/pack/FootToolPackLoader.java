package de.mhus.vance.foot.tools.pack;

import de.mhus.vance.foot.auth.VancePaths;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads pack definitions from two layers and merges them:
 * <ol>
 *   <li>the global {@code foot-tools/} directory under
 *       {@code $VANCE_HOME} / {@code ~/.vancetope} (or the path given by
 *       the {@code vance.foot.tools.dir} Spring property, which replaces
 *       exactly this layer)</li>
 *   <li>the project-local {@code ./.vancetope/foot-tools/}, unless
 *       {@code --no-local} switched project resolution off</li>
 * </ol>
 * Union over both, keyed by pack name — a project definition wins over a
 * global one of the same name, so a repo can re-point or (with
 * {@code "enabled": false}) locally silence an inherited pack. Files
 * named {@code *.json.disabled} are skipped by the glob, which is the
 * rename-to-disable convention.
 *
 * <p>Deliberately the same file format in both layers: a pack is
 * copyable between home and project without a rewrite.
 *
 * <p>Note that project-layer packs are <em>not</em> materialised on
 * trust alone — {@link ProjectPackConsent} gates them, because a pack
 * definition contains a command line that foot would spawn.
 *
 * <p>Stateless and synchronous — the registry calls this on boot and
 * again on {@code /tools reload}.
 */
@Service
@Slf4j
public class FootToolPackLoader {

    /** Directory name holding pack definitions, inside either {@code .vancetope}. */
    public static final String SUBDIR = "foot-tools";

    /** Legacy-compatible default, relative to the user's home. */
    public static final String DEFAULT_DIR = ".vancetope/" + SUBDIR;

    /**
     * Self-built Jackson 3 mapper. Spring Boot 4 still auto-configures
     * Jackson 2's {@code com.fasterxml.jackson.databind.ObjectMapper};
     * the foot uses Jackson 3 ({@code tools.jackson.*}) for protocol
     * handling but doesn't expose a {@link ObjectMapper} bean —
     * other foot services build their own the same way (see
     * {@code ConnectionService}). One mapper per loader is fine: parse
     * cost dwarfs allocation.
     */
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    /** {@code null} in the test constructor — then only the override / no dir applies. */
    private final @Nullable VancePaths paths;

    @Value("${vance.foot.tools.dir:}")
    private @Nullable String configuredDir;

    // Explicitly annotated: with two constructors and none marked,
    // Spring silently prefers the no-arg one, which would leave the
    // loader without any directory to read. Same pattern as VancePaths.
    @Autowired
    public FootToolPackLoader(VancePaths paths) {
        this.paths = paths;
    }

    /** Test constructor — no {@link VancePaths}, so only {@code configuredDir} resolves. */
    FootToolPackLoader() {
        this.paths = null;
    }

    /**
     * The global pack directory: the {@code vance.foot.tools.dir}
     * override when set, else {@code <global .vancetope>/foot-tools}.
     * {@code null} when neither resolves (test JVMs without
     * {@code user.home}).
     */
    public @Nullable Path globalDir() {
        if (configuredDir != null && !configuredDir.isBlank()) {
            return Path.of(configuredDir.trim()).toAbsolutePath();
        }
        if (paths == null) return null;
        Path home = paths.globalHomeDir();
        return home.toString().isBlank() ? null : home.resolve(SUBDIR);
    }

    /**
     * The project-local pack directory, or {@code null} when project
     * resolution is off ({@code --no-local}) or unavailable. Not
     * required to exist — callers tolerate an absent directory.
     */
    public @Nullable Path projectDir() {
        if (paths == null || !paths.isLocalEnabled()) return null;
        Path local = paths.projectLocalDir();
        return local.toString().isBlank() ? null : local.resolve(SUBDIR);
    }

    /**
     * Kept for the diagnostic status line — the global layer is the one
     * users think of as "the config dir".
     */
    public @Nullable Path effectiveDir() {
        return globalDir();
    }

    /**
     * Loads and merges both layers. Returns an empty list when neither
     * directory exists (not a failure). Logs a warning per malformed
     * file but never throws — one bad pack shouldn't block the rest.
     */
    public List<LoadedPack> loadAll() {
        Map<String, LoadedPack> merged = new LinkedHashMap<>();
        Path global = globalDir();
        for (LoadedPack pack : loadDir(global, PackOrigin.GLOBAL)) {
            merged.put(pack.name(), pack);
        }
        Path project = projectDir();
        // An override can point the global layer at the project directory;
        // reading it twice would log a bogus "overrides" line.
        if (project != null && !project.equals(global)) {
            for (LoadedPack pack : loadDir(project, PackOrigin.PROJECT)) {
                LoadedPack shadowed = merged.put(pack.name(), pack);
                if (shadowed != null) {
                    log.info("FootToolPackLoader: project pack '{}' ({}) overrides global {}",
                            pack.name(), pack.file(), shadowed.file());
                }
            }
        }
        return List.copyOf(merged.values());
    }

    private List<LoadedPack> loadDir(@Nullable Path dir, PackOrigin origin) {
        if (dir == null) {
            log.debug("FootToolPackLoader: no {} dir resolvable; skipping", origin);
            return List.of();
        }
        if (!Files.isDirectory(dir)) {
            log.debug("FootToolPackLoader: {} dir '{}' does not exist — nothing to load", origin, dir);
            return List.of();
        }
        List<LoadedPack> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : stream) {
                FootToolPackConfig config = parseSafe(file);
                if (config != null) out.add(new LoadedPack(config, file, origin));
            }
        } catch (IOException e) {
            log.warn("FootToolPackLoader: failed to enumerate '{}': {}", dir, e.toString());
            return List.copyOf(out);
        }
        log.info("FootToolPackLoader: loaded {} {} pack config(s) from {}",
                out.size(), origin, dir);
        return List.copyOf(out);
    }

    /**
     * Reads a single file. Used by tests and (later) admin tooling
     * that wants to validate a pack-config without writing it.
     */
    public @Nullable FootToolPackConfig parseSafe(Path file) {
        try {
            return objectMapper.readValue(file.toFile(), FootToolPackConfig.class);
        } catch (RuntimeException e) {
            log.warn("FootToolPackLoader: skipping invalid pack file '{}': {}",
                    file, e.getMessage());
            return null;
        }
    }
}
