package de.mhus.vance.foot.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccessStoreTest {

    private final AccessStore store = new AccessStore();

    @Test
    void load_absentFile_returnsEmpty(@TempDir Path dir) {
        assertThat(store.load(dir)).isEmpty();
        assertThat(store.exists(dir)).isFalse();
    }

    @Test
    void save_thenLoad_roundTripsAllFields(@TempDir Path dir) {
        AccessData data = new AccessData();
        data.setUsername("mike");
        data.setAccessToken("acc-123");
        data.setAccessExpiresAt(1_700_000_000_000L);
        data.setRefreshToken("ref-456");
        data.setRefreshExpiresAt(1_800_000_000_000L);

        store.save(dir, data);

        Optional<AccessData> loaded = store.load(dir);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getUsername()).isEqualTo("mike");
        assertThat(loaded.get().getAccessToken()).isEqualTo("acc-123");
        assertThat(loaded.get().getAccessExpiresAt()).isEqualTo(1_700_000_000_000L);
        assertThat(loaded.get().getRefreshToken()).isEqualTo("ref-456");
        assertThat(loaded.get().getRefreshExpiresAt()).isEqualTo(1_800_000_000_000L);
    }

    @Test
    void save_createsMissingDirectory(@TempDir Path dir) {
        Path nested = dir.resolve("sub").resolve(".vancetope");
        AccessData data = new AccessData();
        data.setAccessToken("x");

        store.save(nested, data);

        assertThat(store.exists(nested)).isTrue();
    }

    @Test
    void save_restrictsFileToOwnerOnlyOnPosix(@TempDir Path dir) throws Exception {
        Path file = store.file(dir);
        if (!file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return; // not a POSIX file system — nothing to assert
        }
        AccessData data = new AccessData();
        data.setAccessToken("secret");

        store.save(dir, data);

        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
        assertThat(perms).containsExactlyInAnyOrder(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    @Test
    void delete_removesFileAndReportsWhetherItExisted(@TempDir Path dir) {
        AccessData data = new AccessData();
        data.setAccessToken("x");
        store.save(dir, data);

        assertThat(store.delete(dir)).isTrue();
        assertThat(store.exists(dir)).isFalse();
        assertThat(store.delete(dir)).isFalse();
    }

    @Test
    void load_brokenFile_throws(@TempDir Path dir) throws Exception {
        Files.writeString(store.file(dir), "this: is: not: valid: yaml: [");

        assertThatThrownBy(() -> store.load(dir))
                .isInstanceOf(AccessStoreException.class);
    }
}
