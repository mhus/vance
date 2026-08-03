package de.mhus.vance.foot.config;

import de.mhus.vance.foot.auth.AccessStoreException;
import de.mhus.vance.foot.auth.VancePaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Reads and writes the project-local {@code .vancetope/config.yaml}
 * ({@link VanceProjectConfig}). Pure file I/O over an explicit directory —
 * callers pass the directory resolved by {@link VancePaths} so the store
 * stays trivially testable.
 *
 * <p>Mirrors the pattern of {@code ProjectBindingStore} for
 * {@code project.yaml}: absent file → empty Optional, broken file →
 * {@link AccessStoreException}.
 */
@Component
@Slf4j
public class VanceProjectConfigStore {

    public static final String CONFIG_FILE = "config.yaml";

    private final YAMLMapper mapper = (YAMLMapper) YAMLMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /** The {@code config.yaml} path inside {@code dir}. */
    public Path file(Path dir) {
        return dir.resolve(CONFIG_FILE);
    }

    /**
     * Loads the project config from {@code dir/config.yaml}, or empty when
     * the file is absent. A present-but-broken file raises
     * {@link AccessStoreException}.
     */
    public Optional<VanceProjectConfig> load(Path dir) {
        Path file = file(dir);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            VanceProjectConfig config = mapper.readValue(file.toFile(), VanceProjectConfig.class);
            return Optional.ofNullable(config);
        } catch (Exception e) {
            throw new AccessStoreException(
                    "Failed to read project config " + file + ": " + e.getMessage(), e);
        }
    }

    /** Writes the config to {@code dir/config.yaml}, creating {@code dir} if needed. */
    public void save(Path dir, VanceProjectConfig config) {
        Path file = file(dir);
        try {
            Files.createDirectories(dir);
            mapper.writeValue(file.toFile(), config);
            log.debug("wrote project config {}", file);
        } catch (IOException | RuntimeException e) {
            throw new AccessStoreException(
                    "Failed to write project config " + file + ": " + e.getMessage(), e);
        }
    }
}
