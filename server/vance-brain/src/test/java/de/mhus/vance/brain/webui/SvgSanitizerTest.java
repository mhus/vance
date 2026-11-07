package de.mhus.vance.brain.webui;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SvgSanitizerTest {

    private static final String NS = "xmlns=\"http://www.w3.org/2000/svg\"";

    private static Optional<String> clean(String svg) {
        return SvgSanitizer.sanitize(svg.getBytes(StandardCharsets.UTF_8))
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8));
    }

    @Test
    void aPlainIconKeepsItsDrawing() {
        String out = clean("<svg " + NS + " viewBox=\"0 0 24 24\"><path d=\"M0 0h24v24H0z\" fill=\"#4a154b\"/></svg>")
                .orElseThrow();

        assertThat(out)
                .contains("<svg")
                .contains("viewBox=\"0 0 24 24\"")
                .contains("d=\"M0 0h24v24H0z\"")
                .contains("fill=\"#4a154b\"");
    }

    @Test
    void aScriptElementIsRemovedWithItsContent() {
        String out = clean("<svg " + NS + "><script>alert(1)</script><path d=\"M0 0\"/></svg>")
                .orElseThrow();

        assertThat(out).doesNotContain("script").doesNotContain("alert").contains("<path");
    }

    @Test
    void foreignObjectStyleImageAndAnchorsAreRemovedWithTheirSubtrees() {
        String out = clean("<svg " + NS + ">"
                        + "<foreignObject><div>x</div></foreignObject>"
                        + "<style>rect{fill:url(https://evil/x)}</style>"
                        + "<image href=\"https://evil/x.png\"/>"
                        + "<a href=\"https://evil\"><rect width=\"1\" height=\"1\"/></a>"
                        + "<circle r=\"2\"/></svg>")
                .orElseThrow();

        assertThat(out)
                .doesNotContain("foreignObject")
                .doesNotContain("style")
                .doesNotContain("image")
                .doesNotContain("<a")
                .doesNotContain("evil")
                .contains("<circle");
    }

    @Test
    void animationElementsAreRemoved() {
        String out = clean("<svg " + NS + "><path d=\"M0 0\"><animate attributeName=\"d\" to=\"M1 1\"/>"
                        + "<set attributeName=\"fill\" to=\"red\"/></path></svg>")
                .orElseThrow();

        assertThat(out).doesNotContain("animate").doesNotContain("<set").contains("<path");
    }

    @Test
    void eventHandlerAttributesAreRemoved() {
        String out = clean("<svg " + NS + " onload=\"alert(1)\"><rect onclick=\"x()\" width=\"3\"/></svg>")
                .orElseThrow();

        assertThat(out).doesNotContain("onload").doesNotContain("onclick").contains("width=\"3\"");
    }

    @Test
    void aReferenceOutsideTheDocumentIsRemovedButAFragmentStays() {
        String out = clean("<svg " + NS + " xmlns:xlink=\"http://www.w3.org/1999/xlink\">"
                        + "<defs><linearGradient id=\"g\"><stop offset=\"0\"/></linearGradient></defs>"
                        + "<use href=\"https://evil/x.svg#a\"/>"
                        + "<use xlink:href=\"#g\"/>"
                        + "<rect fill=\"url(#g)\" stroke=\"url(https://evil/x)\"/></svg>")
                .orElseThrow();

        assertThat(out).doesNotContain("evil").contains("xlink:href=\"#g\"").contains("fill=\"url(#g)\"");
    }

    @Test
    void aStyleAttributeThatFetchesOrRunsIsRemoved() {
        String out = clean("<svg " + NS + "><rect style=\"fill:url(http://evil/x)\" id=\"a\"/>"
                        + "<rect style=\"width:expression(alert(1))\" id=\"b\"/>"
                        + "<rect style=\"fill:red\" id=\"c\"/></svg>")
                .orElseThrow();

        assertThat(out).doesNotContain("evil").doesNotContain("expression").contains("fill:red");
    }

    @Test
    void aDoctypeIsRefusedSoNoExternalEntityIsEverRead() {
        assertThat(clean("<!DOCTYPE svg [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><svg " + NS + ">&x;</svg>"))
                .isEmpty();
    }

    @Test
    void aDocumentThatIsNoXmlIsRefused() {
        assertThat(clean("<svg " + NS + "><path></svg>")).isEmpty();
        assertThat(clean("not xml at all")).isEmpty();
    }

    @Test
    void aRootThatIsNoSvgIsRefused() {
        assertThat(clean("<html xmlns=\"http://www.w3.org/1999/xhtml\"><body/></html>"))
                .isEmpty();
        assertThat(clean("<svg><path d=\"M0 0\"/></svg>"))
                .as("no SVG namespace")
                .isEmpty();
    }

    @Test
    void commentsAndProcessingInstructionsAreDropped() {
        String out = clean("<svg " + NS + "><!-- secret --><?php echo 1 ?><path d=\"M0 0\"/></svg>")
                .orElseThrow();

        assertThat(out).doesNotContain("secret").doesNotContain("php").contains("<path");
    }

    @Test
    void aCssEscapeThatSpellsUrlDoesNotGetPast() {
        String out = clean("<svg " + NS + "><rect width=\"1\" height=\"1\" style=\"fill:\\75rl(https://evil/x)\"/>"
                        + "<circle r=\"2\" fill=\"\\75rl(https://evil/x)\"/></svg>")
                .orElseThrow();

        assertThat(out)
                .doesNotContain("evil")
                .doesNotContain("style")
                .contains("<rect")
                .contains("<circle");
    }
}
