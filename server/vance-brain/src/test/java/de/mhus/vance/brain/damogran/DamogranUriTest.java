package de.mhus.vance.brain.damogran;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.brain.damogran.DamogranUri.VanceRef;
import org.junit.jupiter.api.Test;

class DamogranUriTest {

    @Test
    void scheme_extractsUriScheme() {
        assertThat(DamogranUri.scheme("vance:hello.tex")).isEqualTo("vance");
        assertThat(DamogranUri.scheme("vance://p/x")).isEqualTo("vance");
        assertThat(DamogranUri.scheme("git:https://x/r.git")).isEqualTo("git");
        assertThat(DamogranUri.scheme("https://x/y")).isEqualTo("https");
        assertThat(DamogranUri.scheme("plain/path")).isEqualTo("");
    }

    @Test
    void parentDir_returnsDocumentFolder() {
        assertThat(DamogranUri.parentDir("documents/tex1/c.yaml")).isEqualTo("documents/tex1");
        assertThat(DamogranUri.parentDir("c.yaml")).isEqualTo("");
    }

    @Test
    void resolveVance_relative_isJoinedOntoBaseDir() {
        VanceRef ref = DamogranUri.resolveVance("documents/tex1", "vance:hello.tex");
        assertThat(ref.project()).isNull();
        assertThat(ref.path()).isEqualTo("documents/tex1/hello.tex");
    }

    @Test
    void resolveVance_leadingSlash_isRootAbsolute() {
        VanceRef ref = DamogranUri.resolveVance("documents/tex1", "vance:/reports/x.pdf");
        assertThat(ref.project()).isNull();
        assertThat(ref.path()).isEqualTo("reports/x.pdf");
    }

    @Test
    void resolveVance_doubleSlash_isCrossProjectRootRelative() {
        VanceRef ref = DamogranUri.resolveVance("documents/tex1", "vance://tud-template/lib/tud.cls");
        assertThat(ref.project()).isEqualTo("tud-template");
        assertThat(ref.path()).isEqualTo("lib/tud.cls");
    }

    @Test
    void resolveVance_blankBaseDir_isRootRelative() {
        VanceRef ref = DamogranUri.resolveVance(null, "vance:hello.tex");
        assertThat(ref.project()).isNull();
        assertThat(ref.path()).isEqualTo("hello.tex");
    }
}
