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

    // ── renameSession ──

    @Test
    void renameSession_setsName_onMatchingEntry(@TempDir Path dir) {
        store.upsertSession(dir, "sess_a", "proj-1", "old-name", 1_000L);

        store.renameSession(dir, "sess_a", "new-name");

        Optional<SessionAnchor> loaded = store.load(dir);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getSessions()).hasSize(1);
        assertThat(loaded.get().getSessions().get(0).getName()).isEqualTo("new-name");
    }

    @Test
    void renameSession_doesNotChangePosition(@TempDir Path dir) {
        store.upsertSession(dir, "sess_a", "proj-1", null, 1_000L);
        store.upsertSession(dir, "sess_b", "proj-1", null, 2_000L);
        store.upsertSession(dir, "sess_c", "proj-1", null, 3_000L);

        // Rename sess_b (position 1, middle) — should stay at position 1.
        store.renameSession(dir, "sess_b", "renamed");

        Optional<SessionAnchor> loaded = store.load(dir);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getSessions()).hasSize(3);
        // Order unchanged: sess_c (newest), sess_b, sess_a
        assertThat(loaded.get().getSessions().get(0).getSessionId()).isEqualTo("sess_c");
        assertThat(loaded.get().getSessions().get(1).getSessionId()).isEqualTo("sess_b");
        assertThat(loaded.get().getSessions().get(1).getName()).isEqualTo("renamed");
        assertThat(loaded.get().getSessions().get(2).getSessionId()).isEqualTo("sess_a");
    }

    @Test
    void renameSession_doesNotChangeUpdatedAt(@TempDir Path dir) {
        store.upsertSession(dir, "sess_a", "proj-1", null, 5_000L);

        store.renameSession(dir, "sess_a", "renamed");

        Optional<SessionAnchor> loaded = store.load(dir);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getSessions().get(0).getUpdatedAt()).isEqualTo(5_000L);
    }

    @Test
    void renameSession_clearsName_whenNullPassed(@TempDir Path dir) {
        store.upsertSession(dir, "sess_a", "proj-1", "original", 1_000L);

        store.renameSession(dir, "sess_a", null);

        Optional<SessionAnchor> loaded = store.load(dir);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getSessions().get(0).getName()).isNull();
    }

    @Test
    void renameSession_noop_whenEntryNotFound(@TempDir Path dir) {
        store.upsertSession(dir, "sess_a", "proj-1", "alpha", 1_000L);

        store.renameSession(dir, "sess_nonexistent", "whatever");

        // File unchanged — name of sess_a is still "alpha".
        Optional<SessionAnchor> loaded = store.load(dir);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getSessions().get(0).getName()).isEqualTo("alpha");
    }

    @Test
    void renameSession_noop_whenAnchorFileAbsent(@TempDir Path dir) {
        // No file written — rename should silently do nothing.
        store.renameSession(dir, "sess_x", "name");

        assertThat(store.load(dir)).isEmpty();
    }

    @Test
    void renameSession_onlyPatchesTargetEntry(@TempDir Path dir) {
        store.upsertSession(dir, "sess_a", "proj-1", "alpha", 1_000L);
        store.upsertSession(dir, "sess_b", "proj-1", "beta", 2_000L);

        store.renameSession(dir, "sess_b", "beta-renamed");

        Optional<SessionAnchor> loaded = store.load(dir);
        assertThat(loaded).isPresent();
        // sess_a untouched.
        assertThat(loaded.get().getSessions().get(1).getName()).isEqualTo("alpha");
        // sess_b renamed.
        assertThat(loaded.get().getSessions().get(0).getName()).isEqualTo("beta-renamed");
    }

    // ── findName ──

    @Test
    void findName_returnsName_whenEntryExists(@TempDir Path dir) {
        store.upsertSession(dir, "sess_a", "proj-1", "frosty-badger", 1_000L);

        assertThat(store.findName(dir, "sess_a")).isEqualTo("frosty-badger");
    }

    @Test
    void findName_returnsNull_whenEntryHasNoName(@TempDir Path dir) {
        store.upsertSession(dir, "sess_a", "proj-1", null, 1_000L);

        assertThat(store.findName(dir, "sess_a")).isNull();
    }

    @Test
    void findName_returnsNull_whenEntryNotFound(@TempDir Path dir) {
        store.upsertSession(dir, "sess_a", "proj-1", "alpha", 1_000L);

        assertThat(store.findName(dir, "sess_nonexistent")).isNull();
    }

    @Test
    void findName_returnsNull_whenAnchorFileAbsent(@TempDir Path dir) {
        assertThat(store.findName(dir, "sess_x")).isNull();
    }

    @Test
    void findName_returnsCorrectName_fromMultipleEntries(@TempDir Path dir) {
        store.upsertSession(dir, "sess_a", "proj-1", "alpha", 1_000L);
        store.upsertSession(dir, "sess_b", "proj-1", "beta", 2_000L);
        store.upsertSession(dir, "sess_c", "proj-1", "gamma", 3_000L);

        assertThat(store.findName(dir, "sess_a")).isEqualTo("alpha");
        assertThat(store.findName(dir, "sess_b")).isEqualTo("beta");
        assertThat(store.findName(dir, "sess_c")).isEqualTo("gamma");
    }

    @Test
    void findName_reflectsRename(@TempDir Path dir) {
        store.upsertSession(dir, "sess_a", "proj-1", "old-name", 1_000L);

        store.renameSession(dir, "sess_a", "new-name");

        assertThat(store.findName(dir, "sess_a")).isEqualTo("new-name");
    }

    // ── loadEntries ──

    @Test
    void loadEntries_absentFile_returnsEmpty(@TempDir Path dir) {
        assertThat(store.loadEntries(dir)).isEmpty();
    }

    @Test
    void loadEntries_returnsNewestFirst(@TempDir Path dir) {
        store.upsertSession(dir, "sess_old", "proj-1", "old", 1_000L);
        store.upsertSession(dir, "sess_new", "proj-1", "new", 3_000L);
        store.upsertSession(dir, "sess_mid", "proj-1", "mid", 2_000L);

        assertThat(store.loadEntries(dir))
                .extracting(SessionAnchor.SessionEntry::getSessionId)
                .containsExactly("sess_new", "sess_mid", "sess_old");
    }

    @Test
    void loadEntries_dropsNullAndBlankIds(@TempDir Path dir) {
        SessionAnchor anchor = new SessionAnchor();
        anchor.setSessions(java.util.List.of(
                new SessionAnchor.SessionEntry("sess_ok", "proj-1", "ok", 1_000L),
                new SessionAnchor.SessionEntry(null, "proj-2", "no-id", 2_000L),
                new SessionAnchor.SessionEntry("   ", "proj-3", "blank-id", 3_000L)));
        store.save(dir, anchor);

        assertThat(store.loadEntries(dir))
                .extracting(SessionAnchor.SessionEntry::getSessionId)
                .containsExactly("sess_ok");
    }

    @Test
    void loadEntries_nullUpdatedAt_sortsLowest(@TempDir Path dir) {
        SessionAnchor anchor = new SessionAnchor();
        anchor.setSessions(java.util.List.of(
                new SessionAnchor.SessionEntry("sess_a", "proj-1", "a", null),
                new SessionAnchor.SessionEntry("sess_b", "proj-1", "b", 5_000L)));
        store.save(dir, anchor);

        assertThat(store.loadEntries(dir))
                .extracting(SessionAnchor.SessionEntry::getSessionId)
                .containsExactly("sess_b", "sess_a");
    }
}
