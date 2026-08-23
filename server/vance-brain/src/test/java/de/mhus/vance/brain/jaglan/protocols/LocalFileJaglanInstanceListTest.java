package de.mhus.vance.brain.jaglan.protocols;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import de.mhus.vance.api.mount.MountedStat;
import de.mhus.vance.shared.workspace.WorkspaceRootService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Confinement of the {@code local} listing.
 *
 * <p>{@code resolve()} runs every addressed path through
 * {@link WorkspaceRootService}, but the children of a folder arrive from
 * {@code Files.list} and used to go straight into {@code toStat} — which
 * follows symlinks. A link inside the mount pointing at {@code /etc/passwd}
 * therefore appeared in the listing with the target's size, mtime and mime
 * type: no content leak, because {@code open()} still refuses, but a metadata
 * leak and a row promising a read that cannot happen.
 *
 * <p>Built against the instance directly rather than through the protocol so
 * this stays independent of how a mount comes to be configured.
 */
class LocalFileJaglanInstanceListTest {

    @TempDir
    Path root;

    private LocalFileJaglanInstance instance;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(root.resolve("books"));
        Files.writeString(root.resolve("readme.md"), "hello");
        instance = new LocalFileJaglanInstance(
                "library", root, false, Duration.ofSeconds(60), "Local", new WorkspaceRootService());
    }

    /** @return {@code false} when the filesystem has no symlinks to test with */
    private boolean link(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (UnsupportedOperationException | IOException e) {
            return false;
        }
    }

    @Test
    void list_symlinkOutOfTheRoot_isSkipped() throws IOException {
        Path outside = Files.createTempDirectory("jaglan-outside");
        Path secret = Files.writeString(outside.resolve("passwd"), "root:x:0:0:");
        assumeThat(link(root.resolve("passwd"), secret)).isTrue();

        assertThat(instance.list("")).extracting(MountedStat::path)
                .containsExactlyInAnyOrder("books", "readme.md");
    }

    @Test
    void list_symlinkOutOfTheRoot_doesNotFailTheWholeFolder() throws IOException {
        Path outside = Files.createTempDirectory("jaglan-outside");
        assumeThat(link(root.resolve("escape"), outside)).isTrue();

        // Skipped, not thrown: one bad entry must not take the folder down.
        assertThat(instance.list("")).isNotEmpty();
    }

    @Test
    void list_symlinkInsideTheRoot_isStillListed() {
        assumeThat(link(root.resolve("alias.md"), root.resolve("readme.md"))).isTrue();

        // The filter is about leaving the mount, not about symlinks as such.
        assertThat(instance.list("")).extracting(MountedStat::path).contains("alias.md");
    }
}
