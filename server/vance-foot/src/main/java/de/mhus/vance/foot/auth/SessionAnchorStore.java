package de.mhus.vance.foot.auth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Reads and writes {@code .vancetope/session.yaml} ({@link SessionAnchor}).
 * Pure file I/O over an explicit directory — callers pass the directory
 * resolved by {@link VancePaths} so the store stays trivially testable.
 * Mirrors {@link ProjectBindingStore}.
 */
@Component
@Slf4j
public class SessionAnchorStore {

    private final YAMLMapper mapper = (YAMLMapper) YAMLMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /** The {@code session.yaml} path inside {@code dir}. */
    public Path file(Path dir) {
        return dir.resolve(VancePaths.SESSION_FILE);
    }

    /**
     * Loads the anchor from {@code dir/session.yaml}, or empty when the file
     * is absent. A present-but-broken file raises {@link AccessStoreException}.
     */
    public Optional<SessionAnchor> load(Path dir) {
        Path file = file(dir);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            SessionAnchor anchor = mapper.readValue(file.toFile(), SessionAnchor.class);
            return Optional.ofNullable(anchor);
        } catch (Exception e) {
            throw new AccessStoreException(
                    "Failed to read session anchor " + file + ": " + e.getMessage(), e);
        }
    }

    /**
     * The stored session id in {@code dir}, or {@code null} when there is no
     * anchor (or it carries no id). Convenience for the {@code --continue} path.
     */
    public @Nullable String loadSessionId(Path dir) {
        return load(dir)
                .map(SessionAnchor::getSessionId)
                .filter(id -> id != null && !id.isBlank())
                .orElse(null);
    }

    /** Writes the anchor to {@code dir/session.yaml}, creating {@code dir} if needed. */
    public void save(Path dir, SessionAnchor anchor) {
        Path file = file(dir);
        try {
            Files.createDirectories(dir);
            mapper.writeValue(file.toFile(), anchor);
            log.debug("wrote session anchor {}", file);
        } catch (IOException | RuntimeException e) {
            throw new AccessStoreException(
                    "Failed to write session anchor " + file + ": " + e.getMessage(), e);
        }
    }
}
