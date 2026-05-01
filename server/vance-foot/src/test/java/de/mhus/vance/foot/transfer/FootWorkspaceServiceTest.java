package de.mhus.vance.foot.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.foot.config.FootConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

class FootWorkspaceServiceTest {

    @TempDir
    Path root;

    FootWorkspaceProperties properties;
    FootConfig footConfig;
    FootWorkspaceService service;

    @BeforeEach
    void setUp() {
        properties = new FootWorkspaceProperties();
        properties.setRoot(root.toString());
        footConfig = new FootConfig();
        footConfig.getAuth().setTenant("acme");
        service = new FootWorkspaceService(properties, footConfig);
    }

    @Test
    void resolveForWriteCreatesProjectRoot() throws Exception {
        Path resolved = service.resolveForWrite("p1", "soundfiles/foo.wav");
        Path expected = root.resolve("acme/p1").toRealPath().resolve("soundfiles/foo.wav");
        assertThat(resolved).isEqualTo(expected);
        assertThat(Files.isDirectory(resolved.getParent())).isTrue();
    }

    @Test
    void resolveForWriteRejectsParentEscape() {
        assertThatThrownBy(() ->
                service.resolveForWrite("p1", "../../../etc/passwd"))
                .isInstanceOf(TransferPathException.class)
                .hasMessageContaining("escapes");
    }

    @Test
    void resolveForWriteStripsLeadingSlash() throws Exception {
        Path resolved = service.resolveForWrite("p1", "/foo/bar.txt");
        Path expected = root.resolve("acme/p1").toRealPath().resolve("foo/bar.txt");
        assertThat(resolved).isEqualTo(expected);
    }

    @Test
    void resolveForWriteRejectsAbsoluteEscape() {
        // Even after stripping leading slashes, normalize() will collapse "../"
        // segments and the startsWith check catches the escape.
        assertThatThrownBy(() ->
                service.resolveForWrite("p1", "/../escape.txt"))
                .isInstanceOf(TransferPathException.class);
    }

    @Test
    void resolveForReadRejectsMissingFile() {
        assertThatThrownBy(() -> service.resolveForRead("p1", "missing.txt"))
                .isInstanceOf(TransferPathException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void resolveForReadAcceptsExistingFile() throws Exception {
        Path target = service.resolveForWrite("p1", "ok.txt");
        Files.writeString(target, "hi");
        Path resolved = service.resolveForRead("p1", "ok.txt");
        assertThat(resolved).isEqualTo(target);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void resolveForReadRejectsSymlinkEscape() throws Exception {
        // Plant a symlink inside the project sandbox that points outside.
        Path projectRoot = service.projectRoot("p1");
        Path outside = root.resolve("outside.txt");
        Files.writeString(outside, "secret");
        Path link = projectRoot.resolve("link.txt");
        Files.createSymbolicLink(link, outside);

        assertThatThrownBy(() -> service.resolveForRead("p1", "link.txt"))
                .isInstanceOf(TransferPathException.class)
                .hasMessageContaining("symlink");
    }

    @Test
    void blankProjectIdRejected() {
        assertThatThrownBy(() -> service.resolveForWrite("", "foo.txt"))
                .isInstanceOf(TransferPathException.class);
    }

    @Test
    void blankUserPathRejected() {
        assertThatThrownBy(() -> service.resolveForWrite("p1", "  "))
                .isInstanceOf(TransferPathException.class);
    }

    @Test
    void emptyTenantFallsBackToUnderscore() throws Exception {
        footConfig.getAuth().setTenant("");
        Path resolved = service.resolveForWrite("p2", "x.txt");
        Path expected = root.resolve("_/p2").toRealPath().resolve("x.txt");
        assertThat(resolved).isEqualTo(expected);
    }
}
