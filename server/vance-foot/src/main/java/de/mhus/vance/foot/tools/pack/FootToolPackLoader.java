package de.mhus.vance.foot.tools.pack;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads {@code ~/.vance/foot-tools/*.json} (or the path given by the
 * {@code vance.foot.tools.dir} Spring property) and parses each entry
 * to a {@link FootToolPackConfig}. Files with the suffix
 * {@code .json.disabled} are skipped — the rename-to-disable
 * convention. Files with {@code "enabled": false} in their JSON body
 * are also marked disabled (parsed but flagged).
 *
 * <p>Stateless and synchronous — the registry calls this on boot
 * (background thread) and again on {@code /tools reload}.
 */
@Service
@Slf4j
public class FootToolPackLoader {

    /**
     * Default location relative to the user's home — created on first
     * read if missing, but only logged at info; foot tolerates an
     * absent directory so users without packs aren't burdened.
     */
    public static final String DEFAULT_DIR = ".vance/foot-tools";

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

    @Value("${vance.foot.tools.dir:}")
    private String configuredDir;

    /**
     * Resolves the effective config directory. Spring-property override
     * wins; falls through to {@code ~/.vance/foot-tools}. Returns
     * {@code null} if {@code user.home} isn't set <i>and</i> no override
     * is configured (test JVMs, sandboxes).
     */
    public Path effectiveDir() {
        if (configuredDir != null && !configuredDir.isBlank()) {
            return Path.of(configuredDir.trim()).toAbsolutePath();
        }
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) return null;
        return Path.of(home, DEFAULT_DIR);
    }

    /**
     * Loads all packs in the effective directory. Returns an empty
     * list if the directory doesn't exist (not a failure). Logs a
     * warning per malformed file but never throws — one bad pack
     * shouldn't block the rest.
     */
    public List<FootToolPackConfig> loadAll() {
        Path dir = effectiveDir();
        if (dir == null) {
            log.debug("FootToolPackLoader: no config dir resolvable; skipping");
            return List.of();
        }
        if (!Files.isDirectory(dir)) {
            log.debug("FootToolPackLoader: dir '{}' does not exist — no foot tool packs to load", dir);
            return List.of();
        }
        List<FootToolPackConfig> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : stream) {
                FootToolPackConfig config = parseSafe(file);
                if (config != null) out.add(config);
            }
        } catch (IOException e) {
            log.warn("FootToolPackLoader: failed to enumerate '{}': {}", dir, e.toString());
            return List.copyOf(out);
        }
        log.info("FootToolPackLoader: loaded {} pack config(s) from {}", out.size(), dir);
        return List.copyOf(out);
    }

    /**
     * Reads a single file. Used by tests and (later) admin tooling
     * that wants to validate a pack-config without writing it.
     */
    public FootToolPackConfig parseSafe(Path file) {
        try {
            return objectMapper.readValue(file.toFile(), FootToolPackConfig.class);
        } catch (RuntimeException e) {
            log.warn("FootToolPackLoader: skipping invalid pack file '{}': {}",
                    file, e.getMessage());
            return null;
        }
    }
}
