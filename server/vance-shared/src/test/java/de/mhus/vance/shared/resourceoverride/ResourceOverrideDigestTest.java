package de.mhus.vance.shared.resourceoverride;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ResourceOverrideDigestTest {

    private static ResourceOverrideFile file(String path, String content) {
        return new ResourceOverrideFile(path, content);
    }

    @Test
    void digest_sameFilesInAnyOrder_isEqual() {
        String a = ResourceOverrideDigest.of(List.of(file("p/a.yaml", "1"), file("p/b.yaml", "2")));
        String b = ResourceOverrideDigest.of(List.of(file("p/b.yaml", "2"), file("p/a.yaml", "1")));

        assertThat(a).isEqualTo(b);
    }

    @Test
    void digest_changedContentOrPath_differs() {
        String base = ResourceOverrideDigest.of(List.of(file("p/a.yaml", "1")));

        assertThat(ResourceOverrideDigest.of(List.of(file("p/a.yaml", "2")))).isNotEqualTo(base);
        assertThat(ResourceOverrideDigest.of(List.of(file("p/c.yaml", "1")))).isNotEqualTo(base);
    }

    @Test
    void digest_contentShiftedAcrossPathBoundary_differs() {
        String a = ResourceOverrideDigest.of(List.of(file("ab", "c")));
        String b = ResourceOverrideDigest.of(List.of(file("a", "bc")));

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void digest_noFiles_isStable() {
        assertThat(ResourceOverrideDigest.of(List.of())).hasSize(64).isEqualTo(ResourceOverrideDigest.of(List.of()));
    }
}
