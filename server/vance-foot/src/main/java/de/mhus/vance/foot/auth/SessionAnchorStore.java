package de.mhus.vance.foot.auth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
     * Migrates the legacy single-session format into the sessions list.
     */
    public Optional<SessionAnchor> load(Path dir) {
        Path file = file(dir);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            SessionAnchor anchor = mapper.readValue(file.toFile(), SessionAnchor.class);
            if (anchor == null) {
                return Optional.empty();
            }
            migrateLegacy(anchor);
            return Optional.of(anchor);
        } catch (Exception e) {
            throw new AccessStoreException(
                    "Failed to read session anchor " + file + ": " + e.getMessage(), e);
        }
    }

    /**
     * The stored session id in {@code dir}, or {@code null} when there is no
     * anchor (or it carries no id). Convenience for the {@code --continue} path.
     * Returns the newest entry's session id.
     */
    public @Nullable String loadSessionId(Path dir) {
        return loadSessionId(dir, null);
    }

    /**
     * The stored session id in {@code dir}, filtered by {@code name} (or
     * the newest entry when {@code name} is null/blank). Returns {@code null}
     * when there is no matching entry.
     */
    public @Nullable String loadSessionId(Path dir, @Nullable String name) {
        return load(dir)
                .map(SessionAnchor::getSessions)
                .map(entries -> findNewest(entries, name))
                .map(SessionAnchor.SessionEntry::getSessionId)
                .filter(id -> id != null && !id.isBlank())
                .orElse(null);
    }

    /**
     * Upserts a session entry into the anchor: if an entry with the same
     * {@code sessionId} already exists, its {@code projectId}, {@code name},
     * and {@code updatedAt} are updated and it is moved to the front.
     * Otherwise a new entry is inserted at the front. The list is trimmed
     * to {@link SessionAnchor#MAX_ENTRIES}. Writes the result to
     * {@code dir/session.yaml}, creating {@code dir} if needed.
     */
    public void upsertSession(Path dir, String sessionId, @Nullable String projectId,
                              @Nullable String name) {
        upsertSession(dir, sessionId, projectId, name, System.currentTimeMillis());
    }

    /**
     * Upserts with an explicit {@code updatedAt} — mainly for tests.
     */
    public void upsertSession(Path dir, String sessionId, @Nullable String projectId,
                              @Nullable String name, long updatedAt) {
        SessionAnchor anchor = load(dir).orElseGet(SessionAnchor::new);
        List<SessionAnchor.SessionEntry> entries = anchor.sessionsOrEmpty();

        // Remove existing entry with same sessionId (if any).
        entries.removeIf(e -> sessionId.equals(e.getSessionId()));

        // Insert new entry at front.
        entries.add(0, new SessionAnchor.SessionEntry(sessionId, projectId, name, updatedAt));

        // Trim to max.
        while (entries.size() > SessionAnchor.MAX_ENTRIES) {
            entries.remove(entries.size() - 1);
        }

        anchor.setSessions(entries);
        // Clear legacy fields — they are redundant now.
        anchor.setSessionId(null);
        anchor.setProjectId(null);
        anchor.setUpdatedAt(null);

        save(dir, anchor);
    }

    /**
     * Writes the anchor to {@code dir/session.yaml}, creating {@code dir} if needed.
     */
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

    // ── Internal ──

    /**
     * If the anchor was loaded from a legacy file (single sessionId at top
     * level, no sessions list), migrate it into a single-entry sessions list.
     */
    private void migrateLegacy(SessionAnchor anchor) {
        if (anchor.getSessions() != null && !anchor.getSessions().isEmpty()) {
            return; // already new format
        }
        String legacyId = anchor.getSessionId();
        if (legacyId == null || legacyId.isBlank()) {
            return; // nothing to migrate
        }
        List<SessionAnchor.SessionEntry> entries = new ArrayList<>();
        entries.add(new SessionAnchor.SessionEntry(
                legacyId, anchor.getProjectId(), null, anchor.getUpdatedAt()));
        anchor.setSessions(entries);
        log.debug("migrated legacy session anchor for {}", legacyId);
    }

    /**
     * Finds the newest entry, optionally filtered by name. Always sorts by
     * {@code updatedAt} descending first (defensive — upsert inserts at
     * front, but a hand-edited file or legacy migration might not be sorted).
     * When {@code name} is null or blank, returns the first entry (newest).
     * When {@code name} is set, returns the first entry whose name matches.
     */
    private SessionAnchor.SessionEntry findNewest(
            List<SessionAnchor.SessionEntry> entries, @Nullable String name) {
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        List<SessionAnchor.SessionEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing(
                (SessionAnchor.SessionEntry e) -> e.getUpdatedAt() == null ? 0L : e.getUpdatedAt(),
                Comparator.reverseOrder()));
        if (name == null || name.isBlank()) {
            return sorted.get(0);
        }
        return sorted.stream()
                .filter(e -> name.equals(e.getName()))
                .findFirst()
                .orElse(null);
    }
}
