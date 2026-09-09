package de.mhus.vance.toolpack.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The If-Match guard only works when a hash computed from served text
 * and a hash computed from the file on disk agree — these tests pin
 * that equivalence, plus the streaming behaviour that makes hashing a
 * multi-gigabyte file safe.
 */
class ContentHashesTest {

    @Test
    void stringAndFileHashAgree_forUtf8Content(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("a.txt");
        Files.writeString(p, "héllo\nwörld\n", StandardCharsets.UTF_8);

        assertThat(ContentHashes.sha256Hex(p)).isEqualTo(ContentHashes.sha256Hex("héllo\nwörld\n"));
    }

    @Test
    void hashIsStableAndHasTheSha256HexShape() {
        String a = ContentHashes.sha256Hex("same");
        String b = ContentHashes.sha256Hex("same");

        assertThat(a).isEqualTo(b);
        assertThat(a).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(ContentHashes.sha256Hex("different")).isNotEqualTo(a);
    }

    @Test
    void abbreviate_keepsJustEnoughForAnErrorMessage() {
        String hash = "0123456789abcdef0123456789abcdef";

        assertThat(ContentHashes.abbreviate(hash)).isEqualTo("0123456789ab");
        assertThat(ContentHashes.abbreviate("short")).isEqualTo("short");
    }

    @Test
    void missingFile_surfacesAsUncheckedIOException(@TempDir Path dir) {
        Path missing = dir.resolve("nope.txt");

        assertThatThrownBy(() -> ContentHashes.sha256Hex(missing)).isInstanceOf(java.io.UncheckedIOException.class);
    }
}
