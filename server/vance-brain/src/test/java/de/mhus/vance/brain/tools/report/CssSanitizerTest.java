package de.mhus.vance.brain.tools.report;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link CssSanitizer} — the web-preview CSS filter. Each test pins one
 * removal so a regression that lets a construct through is named, not
 * just "test failed". The {@code url()} tests are the most important: the
 * browser is a real resource loader, so a surviving external {@code url()}
 * is a fired request (Exfil / SSRF), not a styling artefact.
 */
class CssSanitizerTest {

    @Test
    void sanitize_null_returnsEmpty() {
        assertThat(CssSanitizer.sanitize(null)).isEmpty();
    }

    @Test
    void sanitize_empty_returnsEmpty() {
        assertThat(CssSanitizer.sanitize("")).isEmpty();
    }

    @Test
    void sanitize_plainCss_passesThrough() {
        String css = "h1 { color: red; }\n.pre { background: #f4f8fc; }";
        assertThat(CssSanitizer.sanitize(css)).isEqualTo(css);
    }

    @Test
    void sanitize_atImport_dropped() {
        String css = "@import 'https://evil/x.css';\nh1 { color: red; }";
        assertThat(CssSanitizer.sanitize(css))
            .doesNotContain("@import")
            .doesNotContain("evil")
            .contains("h1 { color: red; }");
    }

    @Test
    void sanitize_atImportWithoutQuotes_dropped() {
        String css = "@import url(https://evil/x.css);\nh1 { color: red; }";
        assertThat(CssSanitizer.sanitize(css))
            .doesNotContain("@import")
            .doesNotContain("evil");
    }

    @Test
    void sanitize_atMediaPreserved() {
        String css = "@media screen { h1 { color: red; } }";
        assertThat(CssSanitizer.sanitize(css)).isEqualTo(css);
    }

    @Test
    void sanitize_atPagePreserved() {
        String css = "@page { margin: 15mm; }";
        assertThat(CssSanitizer.sanitize(css)).isEqualTo(css);
    }

    @Test
    void sanitize_externalHttpsUrl_replacedWithEmptyUrl() {
        String css = "background: url('https://evil/x.png') red;";
        String result = CssSanitizer.sanitize(css);
        // The url() is neutered to url() (no argument) so the fallback
        // color still wins. The external host must NOT survive.
        assertThat(result).contains("url()").doesNotContain("evil").contains("red");
    }

    @Test
    void sanitize_externalHttpUrl_replacedWithEmptyUrl() {
        String css = "background: url(http://evil/x.png);";
        assertThat(CssSanitizer.sanitize(css)).contains("url()").doesNotContain("evil");
    }

    @Test
    void sanitize_fileUrl_replacedWithEmptyUrl() {
        String css = "background: url(file:///etc/passwd);";
        assertThat(CssSanitizer.sanitize(css)).contains("url()").doesNotContain("passwd");
    }

    @Test
    void sanitize_jarUrl_replacedWithEmptyUrl() {
        String css = "background: url(jar:file:/x.jar!/y.png);";
        assertThat(CssSanitizer.sanitize(css)).contains("url()").doesNotContain("jar:");
    }

    @Test
    void sanitize_dataUrl_preserved() {
        String css = "background: url(data:image/png;base64,iVBOR=) no-repeat;";
        assertThat(CssSanitizer.sanitize(css))
            .contains("url(data:image/png;base64,iVBOR=)")
            .contains("no-repeat");
    }

    @Test
    void sanitize_relativeUrl_replacedWithEmptyUrl() {
        // Relative urls in a browser resolve against the document base,
        // which is the brain — a theme-relative path would hit a brain
        // route. v1 disallows all non-data url() to avoid that surface.
        String css = "background: url(images/bg.png);";
        assertThat(CssSanitizer.sanitize(css)).contains("url()").doesNotContain("images/bg.png");
    }

    @Test
    void sanitize_absolutePathUrl_replacedWithEmptyUrl() {
        String css = "background: url(/etc/passwd);";
        assertThat(CssSanitizer.sanitize(css)).contains("url()").doesNotContain("passwd");
    }

    @Test
    void sanitize_javascriptUriInUrl_dropped() {
        String css = "background: url(javascript:alert(1));";
        assertThat(CssSanitizer.sanitize(css)).doesNotContain("javascript");
    }

    @Test
    void sanitize_javascriptUriBare_dropped() {
        String css = "a { href: javascript:alert(1); }";
        assertThat(CssSanitizer.sanitize(css)).doesNotContain("javascript");
    }

    @Test
    void sanitize_expressionIe_dropped() {
        String css = "width: expression(document.body.clientWidth);";
        assertThat(CssSanitizer.sanitize(css))
            .doesNotContain("expression")
            .doesNotContain("document.body");
    }

    @Test
    void sanitize_behaviorIe_dropped() {
        String css = "div { behavior: url(#default#evil); }";
        assertThat(CssSanitizer.sanitize(css))
            .doesNotContain("behavior")
            .doesNotContain("evil");
    }

    @Test
    void sanitize_mozBinding_dropped() {
        String css = "div { -moz-binding: url('https://evil/x.xml'); }";
        String result = CssSanitizer.sanitize(css);
        assertThat(result).doesNotContain("-moz-binding").doesNotContain("evil");
    }

    @Test
    void sanitize_multipleOffenses_allRemoved() {
        String css = "@import 'https://a';\n"
            + "background: url('https://b/x.png');\n"
            + "width: expression(alert(1));\n"
            + "behavior: url(#evil);\n"
            + "h1 { color: red; }";
        String result = CssSanitizer.sanitize(css);
        assertThat(result)
            .doesNotContain("@import")
            .doesNotContain("expression")
            .doesNotContain("behavior")
            .doesNotContain("https://b")
            .contains("h1 { color: red; }");
    }

    @Test
    void sanitize_fontFaceWithDataSrc_preserved() {
        String css = "@font-face { font-family: 'X'; src: url(data:font/woff2;base64,abc=); }";
        assertThat(CssSanitizer.sanitize(css))
            .contains("@font-face")
            .contains("data:font/woff2;base64,abc=");
    }

    @Test
    void sanitize_fontFaceWithExternalSrc_srcNeutered() {
        String css = "@font-face { font-family: 'X'; src: url('https://evil/font.woff2'); }";
        String result = CssSanitizer.sanitize(css);
        assertThat(result)
            .contains("@font-face")
            .doesNotContain("evil")
            .contains("url()");
    }
}
