package de.mhus.vance.foot.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionAnchorStoreTest {

    private final SessionAnchorStore store = new SessionAnchorStore();

    @Test
    void load_absentFile_returnsEmpty(@TempDir Path dir) {
        assertThat(store.load(dir)).isEmpty();
        assertThat(store.loadSessionId(dir)).isNull();
    }

    // ── Legacy migration ──

    @Test
    void load_legacyFormat_migratesToSessionsList(@TempDir Path dir) {
        // Write a legacy single-session file directly.
        SessionAnchor legacy = new SessionAnchor();
        legacy.setSessionId("sess_legacy");
        legacy.setProjectId("proj-old");
        legacy.setUpdatedAt(1_700_000_000_000L);
        // Use save() which writes whatever is on the object — the legacy
        // fields are serialized because they're non-null.
        store.save(dir, legacy);

        Optional<SessionAnchor> loaded = store.load(dir);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getSessions()).hasSize(1);
        assertThat(loaded.get().getSessions().get(0).getSessionId()).isEqualTo("sess_legacy");
        assertThat(loaded.get().getSessions().get(0).getProjectId()).isEqualTo("proj-old");
        assertThat(loaded.get().getSessions().get(0).getUpdatedAt()).isEqualTo(1_700_000_000_000L);
    }

    @Test
    void loadSessionId_legacyFormat_returnsLegacyId(@TempDir Path dir) {
        SessionAnchor legacy = new SessionAnchor();
        legacy.setSessionId("sess_legacy");
        store.save(dir, legacy);

        assertThat(store.loadSessionId(dir)).isEqualTo("sess_legacy");
    }

    // ── upsertSession ──

    @Test
    void upsertSession_createsEntryWhenAbsent(@TempDir Path dir) {
        store.upsertSession(dir, "sess_a", "proj-1", null, 1_000L);

        Optional<SessionAnchor> loaded = store.load(dir);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getSessions()).hasSize(1);
        assertThat(loaded.get().getSessions().get(0).getSessionId()).isEqualTo("sess_a");
        assertThat(loaded.get().getSessions().get(0).getProjectId()).isEqualTo("proj-1");
        assertThat(loaded.get().getSessions().get(0).getName()).isNull();
        assertThat(loaded.get().getSessions().get(0).getUpdatedAt()).isEqualTo(1_000L);
    }

    @Test
    void upsertSession_updatesExistingEntryAndMovesToFront(@TempDir Path dir) {
        store.upsertSession(dir, "sess_a", "proj-1", null, 1_000L);
        store.upsertSession(dir, "sess_b", "proj-1", null, 2_000L);
        store.upsertSession(dir, "sess_c", "proj-1", null, 3_000L);

        // Update sess_a with a newer timestamp — should move to front.
        store.upsertSession(dir, "sess_a", "proj-1", "updated-name", 4_000L);

        Optional<SessionAnchor> loaded = store.load(dir);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getSessions()).hasSize(3);
        assertThat(loaded.get().getSessions().get(0).getSessionId()).isEqualTo("sess_a");
        assertThat(loaded.get().getSessions().get(0).getName()).isEqualTo("updated-name");
        assertThat(loaded.get().getSessions().get(0).getUpdatedAt()).isEqualTo(4_000L);
    }

    @Test
    void upsertSession_deduplicatesBySessionId(@TempDir Path dir) {
        store.upsertSession(dir, "sess_a", "proj-1", null, 1_000L);
        store.upsertSession(dir, "sess_a", "proj-1", null, 2_000L);

        Optional<SessionAnchor> loaded = store.load(dir);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getSessions()).hasSize(1);
        assertThat(loaded.get().getSessions().get(0).getUpdatedAt()).isEqualTo(2_000L);
    }

    @Test
    void upsertSession_trimsToMaxEntries(@TempDir Path dir) {
        for (int i = 0; i < SessionAnchor.MAX_ENTRIES + 5; i++) {
            store.upsertSession(dir, "sess_" + i, "proj-1", null, i);
        }

        Optional<SessionAnchor> loaded = store.load(dir);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getSessions()).hasSize(SessionAnchor.MAX_ENTRIES);
        // Newest should be at front (highest timestamp).
        assertThat(loaded.get().getSessions().get(0).getSessionId()).isEqualTo("sess_" + (SessionAnchor.MAX_ENTRIES + 4));
    }

    // ── loadSessionId ──

    @Test
    void loadSessionId_returnsNewestEntry(@TempDir Path dir) {
        store.upsertSession(dir, "sess_a", "proj-1", null, 1_000L);
        store.upsertSession(dir, "sess_b", "proj-1", null, 3_000L);
        store.upsertSession(dir, "sess_c", "proj-1", null, 2_000L);

        // sess_b has the highest timestamp.
        assertThat(store.loadSessionId(dir)).isEqualTo("sess_b");
    }

    @Test
    void loadSessionId_withNameFilter_returnsMatchingNewest(@TempDir Path dir) {
        store.upsertSession(dir, "sess_a", "proj-1", "alpha", 1_000L);
        store.upsertSession(dir, "sess_b", "proj-1", "beta", 5_000L);
        store.upsertSession(dir, "sess_c", "proj-1", "alpha", 3_000L);

        // alpha's newest is sess_c (3_000 > 1_000).
        assertThat(store.loadSessionId(dir, "alpha")).isEqualTo("sess_c");
        // beta's only entry is sess_b.
        assertThat(store.loadSessionId(dir, "beta")).isEqualTo("sess_b");
    }

    @Test
    void loadSessionId_withNameFilter_noMatch_returnsNull(@TempDir Path dir) {
        store.upsertSession(dir, "sess_a", "proj-1", "alpha", 1_000L);

        assertThat(store.loadSessionId(dir, "gamma")).isNull();
    }

    @Test
    void loadSessionId_blankOrMissingId_returnsNull(@TempDir Path dir) {
        store.upsertSession(dir, "   ", "proj-1", null, 1_000L);

        assertThat(store.loadSessionId(dir)).isNull();
    }

    @Test
    void loadSessionId_emptyFile_returnsNull(@TempDir Path dir) {
        assertThat(store.loadSessionId(dir)).isNull();
    }
}
