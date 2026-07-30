package de.mhus.vance.foot.auth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Reads and writes {@code .vancetope/project.yaml} ({@link ProjectBinding}).
 * Pure file I/O over an explicit directory — callers pass the directory
 * resolved by {@link VancePaths} so the store stays trivially testable.
 */
@Component
@Slf4j
public class ProjectBindingStore {

    private final YAMLMapper mapper = (YAMLMapper) YAMLMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /** The {@code project.yaml} path inside {@code dir}. */
    public Path file(Path dir) {
        return dir.resolve(VancePaths.PROJECT_FILE);
    }

    /**
     * Loads the binding from {@code dir/project.yaml}, or empty when the
     * file is absent. A present-but-broken file raises {@link AccessStoreException}.
     */
    public Optional<ProjectBinding> load(Path dir) {
        Path file = file(dir);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            ProjectBinding binding = mapper.readValue(file.toFile(), ProjectBinding.class);
            return Optional.ofNullable(binding);
        } catch (Exception e) {
            throw new AccessStoreException(
                    "Failed to read project binding " + file + ": " + e.getMessage(), e);
        }
    }

    /** Writes the binding to {@code dir/project.yaml}, creating {@code dir} if needed. */
    public void save(Path dir, ProjectBinding binding) {
        Path file = file(dir);
        try {
            Files.createDirectories(dir);
            mapper.writeValue(file.toFile(), binding);
            log.debug("wrote project binding {}", file);
        } catch (IOException | RuntimeException e) {
            throw new AccessStoreException(
                    "Failed to write project binding " + file + ": " + e.getMessage(), e);
        }
    }
}
