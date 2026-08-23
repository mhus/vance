package de.mhus.vance.brain.jaglan.protocols;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.documents.MountAccess;
import de.mhus.vance.api.mount.MountedStat;
import de.mhus.vance.shared.workspace.WorkspaceRootService;
import de.mhus.vance.toolpack.jaglan.JaglanInstance;
import de.mhus.vance.toolpack.jaglan.JaglanInstanceConfig;
import de.mhus.vance.toolpack.jaglan.JaglanProtocolException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code local} protocol against a real directory — the reason it exists
 * first is that it makes the whole Jaglan path exercisable without a foreign
 * system.
 */
class LocalFileJaglanProtocolTest {

    @TempDir
    Path root;

    private LocalFileJaglanProtocol protocol;

    @BeforeEach
    void setUp() throws IOException {
        // The operator allow-list is what enables the protocol at all — see
        // localProtocol_withoutAnAllowedRoot_isDisabled below.
        protocol = new LocalFileJaglanProtocol(new WorkspaceRootService(), root.toString());
        Files.createDirectories(root.resolve("books"));
        Files.writeString(root.resolve("books/dune.txt"), "spice");
        Files.writeString(root.resolve("readme.md"), "hello");
    }

    private JaglanInstance instance(Map<String, Object> extras) {
        Map<String, Object> merged = new java.util.LinkedHashMap<>();
        merged.put(LocalFileJaglanProtocol.EXTRA_ROOT_DIR, root.toString());
        merged.putAll(extras);
        return protocol.instantiate(new JaglanInstanceConfig(
                "library", LocalFileJaglanProtocol.ID, "", "",
                () -> null, "acme", "research", merged));
    }

    private JaglanInstance readOnly() {
        return instance(Map.of());
    }

    private JaglanInstance writable() {
        return instance(Map.of(LocalFileJaglanProtocol.EXTRA_WRITABLE, "true"));
    }

    // ─── instantiation ──────────────────────────────────────────────────

    @Test
    void instantiate_withoutRootDir_isRefusedUpFront() {
        // Refusing here means the factory drops the mount and it never appears
        // in the tree — better than a folder that opens and then fails.
        assertThatThrownBy(() -> protocol.instantiate(new JaglanInstanceConfig(
                "library", LocalFileJaglanProtocol.ID, "", "",
                () -> null, "acme", "research", Map.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rootDir is required");
    }

    @Test
    void localProtocol_withoutAnAllowedRoot_isDisabled() {
        // rootDir comes from a project-scoped setting, so without an operator
        // allow-list a project admin (or, before the deny list, an installed
        // kit) could point a mount at "/" and read the pod's file system.
        // Empty property = protocol off; a capability this wide must not be
        // obtainable by omission.
        LocalFileJaglanProtocol disabled =
                new LocalFileJaglanProtocol(new WorkspaceRootService(), "");

        assertThatThrownBy(() -> disabled.instantiate(new JaglanInstanceConfig(
                "library", LocalFileJaglanProtocol.ID, "", "",
                () -> null, "acme", "research",
                Map.of(LocalFileJaglanProtocol.EXTRA_ROOT_DIR, root.toString()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(LocalFileJaglanProtocol.ALLOWED_ROOTS_PROPERTY);
    }

    @Test
    void instantiate_rootDirOutsideTheAllowedRoots_isRefused() {
        LocalFileJaglanProtocol narrow = new LocalFileJaglanProtocol(
                new WorkspaceRootService(), root.resolve("books").toString());

        assertThatThrownBy(() -> narrow.instantiate(new JaglanInstanceConfig(
                "library", LocalFileJaglanProtocol.ID, "", "",
                () -> null, "acme", "research",
                Map.of(LocalFileJaglanProtocol.EXTRA_ROOT_DIR, root.toString()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside every directory the operator permitted");
    }

    @Test
    void instantiate_rootDirBelowAnAllowedRoot_isAccepted() {
        // The allow-list names base directories, not exact mounts — a project
        // may pick any subtree of one.
        LocalFileJaglanProtocol wide =
                new LocalFileJaglanProtocol(new WorkspaceRootService(), root.toString());

        assertThat(wide.instantiate(new JaglanInstanceConfig(
                "library", LocalFileJaglanProtocol.ID, "", "",
                () -> null, "acme", "research",
                Map.of(LocalFileJaglanProtocol.EXTRA_ROOT_DIR,
                        root.resolve("books").toString()))))
                .isNotNull();
    }

    @Test
    void instantiate_rootDirThatIsNotADirectory_isRefused() {
        assertThatThrownBy(() -> protocol.instantiate(new JaglanInstanceConfig(
                "library", LocalFileJaglanProtocol.ID, "", "",
                () -> null, "acme", "research",
                Map.of(LocalFileJaglanProtocol.EXTRA_ROOT_DIR,
                        root.resolve("readme.md").toString()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not an existing directory");
    }

    @Test
    void capabilities_defaultToReadOnly() {
        // Writable by omission would be the wrong default in the one place
        // where the blast radius is the machine, not a document.
        assertThat(readOnly().capabilities().access()).isEqualTo(MountAccess.RO);
        assertThat(writable().capabilities().access()).isEqualTo(MountAccess.RW);
    }

    @Test
    void capabilities_ttlDefaultsToASixtySecondWindow() {
        assertThat(readOnly().capabilities().metadataTtl())
                .isEqualTo(LocalFileJaglanProtocol.DEFAULT_TTL);
        assertThat(instance(Map.of(LocalFileJaglanProtocol.EXTRA_TTL_SECONDS, "300"))
                .capabilities().metadataTtl())
                .isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void capabilities_unparseableTtlFallsBackInsteadOfFailing() {
        assertThat(instance(Map.of(LocalFileJaglanProtocol.EXTRA_TTL_SECONDS, "soon"))
                .capabilities().metadataTtl())
                .isEqualTo(LocalFileJaglanProtocol.DEFAULT_TTL);
    }

    @Test
    void capabilities_reportsNoItemCount() {
        // Counting a deep tree on every capabilities fetch would turn a cheap
        // declaration into a filesystem crawl.
        assertThat(readOnly().capabilities().itemCount()).isNull();
        assertThat(readOnly().capabilities().canSearch()).isFalse();
    }

    // ─── read ───────────────────────────────────────────────────────────

    @Test
    void stat_file_carriesSizeMimeAndAnEtag() {
        Optional<MountedStat> stat = readOnly().stat("books/dune.txt");

        assertThat(stat).isPresent();
        assertThat(stat.get().directory()).isFalse();
        assertThat(stat.get().size()).isEqualTo(5);
        assertThat(stat.get().path()).isEqualTo("books/dune.txt");
        // The document layer has no storageId to build a change token from, so
        // the protocol supplies one.
        assertThat(stat.get().etag()).isNotBlank();
        assertThat(stat.get().modifiedAtMs()).isNotNull();
    }

    @Test
    void stat_directory_hasNoSizeAndNoMime() {
        Optional<MountedStat> stat = readOnly().stat("books");

        assertThat(stat).isPresent();
        assertThat(stat.get().directory()).isTrue();
        assertThat(stat.get().size()).isZero();
        assertThat(stat.get().mimeType()).isNull();
    }

    @Test
    void stat_missing_isEmptyNotAnError() {
        // "The source answered and said no" is authoritative; it must not look
        // like a failure, or the shell row would be kept instead of dropped.
        assertThat(readOnly().stat("books/nope.txt")).isEmpty();
    }

    @Test
    void stat_emptyPath_isTheMountRoot() {
        Optional<MountedStat> stat = readOnly().stat("");

        assertThat(stat).isPresent();
        assertThat(stat.get().directory()).isTrue();
    }

    @Test
    void list_returnsOneLevelWithMountRelativePaths() {
        List<MountedStat> entries = readOnly().list("");

        assertThat(entries).extracting(MountedStat::path)
                .containsExactlyInAnyOrder("books", "readme.md");
        assertThat(entries).filteredOn(MountedStat::directory)
                .extracting(MountedStat::path).containsExactly("books");
    }

    @Test
    void list_nestedFolder_prefixesTheChildPaths() {
        assertThat(readOnly().list("books")).extracting(MountedStat::path)
                .containsExactly("books/dune.txt");
    }

    @Test
    void list_ofAFile_isEmptyRatherThanAnError() {
        assertThat(readOnly().list("readme.md")).isEmpty();
    }

    @Test
    void open_streamsTheContent() throws IOException {
        try (InputStream in = readOnly().open("books/dune.txt")) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("spice");
        }
    }

    // ─── confinement ────────────────────────────────────────────────────

    @Test
    void resolve_traversalOutOfTheRoot_isARefusalNotAnOutage() {
        // Retrying will not make an escape legal, so this must be refused —
        // a transient classification would keep the caller trying.
        assertThatThrownBy(() -> readOnly().stat("../../etc/passwd"))
                .isInstanceOf(JaglanProtocolException.class)
                .satisfies(e -> assertThat(((JaglanProtocolException) e).isRefused()).isTrue())
                .hasMessageContaining("escapes mount");
    }

    @Test
    void resolve_absolutePathIsStillConfined() {
        assertThatThrownBy(() -> readOnly().open("/etc/passwd"))
                .isInstanceOf(JaglanProtocolException.class);
    }

    // ─── write and delete ───────────────────────────────────────────────

    @Test
    void write_onAReadOnlyMount_isRefusedWithAnActionableMessage() {
        assertThatThrownBy(() -> readOnly().write("new.txt",
                new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(JaglanProtocolException.class)
                .satisfies(e -> assertThat(((JaglanProtocolException) e).isRefused()).isTrue())
                .hasMessageContaining("writable: true")
                .hasMessageContaining("_vance/config/mounts/");
    }

    @Test
    void delete_onAReadOnlyMount_isRefused() {
        assertThatThrownBy(() -> readOnly().delete("readme.md"))
                .isInstanceOf(JaglanProtocolException.class);
        assertThat(root.resolve("readme.md")).exists();
    }

    @Test
    void write_onAWritableMount_createsTheFileAndReportsItsStat() {
        MountedStat stat = writable().write("notes/new.txt",
                new ByteArrayInputStream("written".getBytes(StandardCharsets.UTF_8)));

        assertThat(root.resolve("notes/new.txt")).exists();
        assertThat(stat.size()).isEqualTo(7);
        assertThat(stat.access()).isEqualTo(MountAccess.RW);
    }

    @Test
    void write_replacesExistingContent() {
        writable().write("readme.md",
                new ByteArrayInputStream("replaced".getBytes(StandardCharsets.UTF_8)));

        assertThat(root.resolve("readme.md")).hasContent("replaced");
    }

    @Test
    void delete_onAWritableMount_removesTheFile() {
        writable().delete("readme.md");

        assertThat(root.resolve("readme.md")).doesNotExist();
    }

    @Test
    void delete_ofAMissingFile_isNotAnError() {
        // deleteIfExists semantics: the caller wanted it gone, and it is.
        writable().delete("never-existed.txt");
    }
}
