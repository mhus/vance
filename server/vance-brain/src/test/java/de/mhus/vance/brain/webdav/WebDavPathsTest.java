package de.mhus.vance.brain.webdav;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class WebDavPathsTest {

    @Test
    void parse_fullFilePath_splitsTenantProjectAndPath() {
        WebDavPaths.Coords coords = WebDavPaths.parse("/brain/acme/webdav/proj/notes/a.md").orElseThrow();
        assertThat(coords.tenantId()).isEqualTo("acme");
        assertThat(coords.project()).isEqualTo("proj");
        assertThat(coords.path()).isEqualTo("notes/a.md");
    }

    @Test
    void parse_projectRootWithoutTrailingSlash_yieldsEmptyPath() {
        WebDavPaths.Coords coords = WebDavPaths.parse("/brain/acme/webdav/proj").orElseThrow();
        assertThat(coords.project()).isEqualTo("proj");
        assertThat(coords.path()).isEmpty();
    }

    @Test
    void parse_projectRootWithTrailingSlash_yieldsEmptyPath() {
        WebDavPaths.Coords coords = WebDavPaths.parse("/brain/acme/webdav/proj/").orElseThrow();
        assertThat(coords.project()).isEqualTo("proj");
        assertThat(coords.path()).isEmpty();
    }

    @Test
    void parse_folderPathWithTrailingSlash_stripsSlash() {
        WebDavPaths.Coords coords = WebDavPaths.parse("/brain/acme/webdav/proj/notes/").orElseThrow();
        assertThat(coords.path()).isEqualTo("notes");
    }

    @Test
    void parse_bareWebdavRoot_hasNullProject() {
        WebDavPaths.Coords coords = WebDavPaths.parse("/brain/acme/webdav").orElseThrow();
        assertThat(coords.tenantId()).isEqualTo("acme");
        assertThat(coords.project()).isNull();
        assertThat(coords.path()).isEmpty();
    }

    @Test
    void parse_percentEncodedSegments_areDecoded() {
        WebDavPaths.Coords coords =
                WebDavPaths.parse("/brain/acme/webdav/proj/my%20notes/a%20b.md").orElseThrow();
        assertThat(coords.path()).isEqualTo("my notes/a b.md");
    }

    @Test
    void parse_nonWebdavPath_isEmpty() {
        Optional<WebDavPaths.Coords> coords = WebDavPaths.parse("/brain/acme/documents/x");
        assertThat(coords).isEmpty();
    }
}
