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

    @Test
    void save_thenLoad_roundTripsAnchor(@TempDir Path dir) {
        SessionAnchor anchor = new SessionAnchor();
        anchor.setSessionId("sess_abc");
        anchor.setProjectId("proj-1");
        anchor.setUpdatedAt(1_700_000_000_000L);

        store.save(dir, anchor);

        Optional<SessionAnchor> loaded = store.load(dir);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getSessionId()).isEqualTo("sess_abc");
        assertThat(loaded.get().getProjectId()).isEqualTo("proj-1");
        assertThat(loaded.get().getUpdatedAt()).isEqualTo(1_700_000_000_000L);
    }

    @Test
    void loadSessionId_returnsStoredId(@TempDir Path dir) {
        SessionAnchor anchor = new SessionAnchor();
        anchor.setSessionId("sess_xyz");
        store.save(dir, anchor);

        assertThat(store.loadSessionId(dir)).isEqualTo("sess_xyz");
    }

    @Test
    void loadSessionId_blankOrMissingId_returnsNull(@TempDir Path dir) {
        SessionAnchor anchor = new SessionAnchor();
        anchor.setSessionId("   ");
        store.save(dir, anchor);

        assertThat(store.loadSessionId(dir)).isNull();
    }

    @Test
    void save_overwritesPreviousAnchor(@TempDir Path dir) {
        SessionAnchor first = new SessionAnchor();
        first.setSessionId("sess_old");
        store.save(dir, first);

        SessionAnchor second = new SessionAnchor();
        second.setSessionId("sess_new");
        store.save(dir, second);

        assertThat(store.loadSessionId(dir)).isEqualTo("sess_new");
    }
}
