package de.mhus.vance.brain.kit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guards the symlink-rejection contract of {@link KitTree}
 * (code-review B1: a malicious kit must not be able to read arbitrary
 * server files via a symlink in its build tree).
 */
class KitTreeTest {

    @Test
    void walkNoSymlinks_returnsEntries_forPlainTree(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("a.md"), "A");
        Path sub = Files.createDirectory(root.resolve("sub"));
        Files.writeString(sub.resolve("b.md"), "B");

        List<Path> entries = KitTree.walkNoSymlinks(root);

        assertThat(entries).contains(root.resolve("a.md"), sub, sub.resolve("b.md"));
    }

    @Test
    void walkNoSymlinks_rejects_symlinkToFile(@TempDir Path root) throws IOException {
        Path secret = Files.writeString(root.resolve("secret.txt"), "TOP SECRET");
        Path link = root.resolve("leak.md");
        assumeTrue(canSymlink(link, secret), "filesystem does not support symlinks");

        assertThatThrownBy(() -> KitTree.walkNoSymlinks(root))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("symbolic link");
    }

    @Test
    void walkNoSymlinks_rejects_symlinkToDirectory(@TempDir Path root) throws IOException {
        Path outside = Files.createDirectory(root.resolve("outside"));
        Files.writeString(outside.resolve("x.md"), "x");
        Path docs = Files.createDirectory(root.resolve("docs"));
        Path link = docs.resolve("evil");
        assumeTrue(canSymlink(link, outside), "filesystem does not support symlinks");

        assertThatThrownBy(() -> KitTree.walkNoSymlinks(docs))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("symbolic link");
    }

    @Test
    void listNoSymlinks_rejects_symlink(@TempDir Path root) throws IOException {
        Path secret = Files.writeString(root.resolve("secret.txt"), "S");
        Path link = root.resolve("x.setting.yaml");
        assumeTrue(canSymlink(link, secret), "filesystem does not support symlinks");

        assertThatThrownBy(() -> KitTree.listNoSymlinks(root))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("symbolic link");
    }

    private static boolean canSymlink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            return false;
        }
    }
}
