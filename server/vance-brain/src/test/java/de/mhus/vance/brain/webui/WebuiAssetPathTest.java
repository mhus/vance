package de.mhus.vance.brain.webui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class WebuiAssetPathTest {

    @Test
    void anAddressBecomesACascadePathUnderTheWebuiRoot() {
        WebuiAssetPath path = WebuiAssetPath.parse("/icons/slack.svg").orElseThrow();

        assertThat(path.cascadePath()).isEqualTo("_vance/webui/icons/slack.svg");
        assertThat(path.type()).isEqualTo(WebuiAssetPath.Type.SVG);
    }

    @Test
    void aLeadingSlashIsOptionalAndSubfoldersAreAllowed() {
        assertThat(WebuiAssetPath.parse("themes/wowbagger/fries.png")
                        .orElseThrow()
                        .cascadePath())
                .isEqualTo("_vance/webui/themes/wowbagger/fries.png");
    }

    @Test
    void theEndingDecidesTheType() {
        assertThat(WebuiAssetPath.parse("a.png").orElseThrow().type()).isEqualTo(WebuiAssetPath.Type.PNG);
        assertThat(WebuiAssetPath.parse("a.jpg").orElseThrow().type()).isEqualTo(WebuiAssetPath.Type.JPEG);
        assertThat(WebuiAssetPath.parse("a.jpeg").orElseThrow().type()).isEqualTo(WebuiAssetPath.Type.JPEG);
        assertThat(WebuiAssetPath.parse("f.woff2").orElseThrow().type()).isEqualTo(WebuiAssetPath.Type.WOFF2);
    }

    static Stream<String> refused() {
        return Stream.of(
                "",
                "/",
                "icons/",
                "icons//slack.svg",
                "../etc/passwd.png",
                "icons/../slack.svg",
                "icons/..svg",
                "icons/a..b.svg",
                "./slack.svg",
                "icons\\slack.svg",
                "icons/%2e%2e/slack.svg",
                "icons/Slack.svg",
                "icons/slack.SVG",
                "icons/sl ack.svg",
                "icons/slack",
                "icons/slack.",
                "icons/.svg",
                "icons/slack.css",
                "icons/slack.html",
                "icons/slack.svg.md",
                "icons/slack.svg.license.md",
                "a/b/c/d/e/f/g.svg");
    }

    @ParameterizedTest
    @MethodSource("refused")
    void whatIsNotAnAssetAddressIsRefused(String requested) {
        assertThat(WebuiAssetPath.parse(requested)).isEmpty();
    }

    @Test
    void sixSegmentsAreTheLimit() {
        assertThat(WebuiAssetPath.parse("a/b/c/d/e/f.svg")).isPresent();
        assertThat(WebuiAssetPath.parse("a/b/c/d/e/f/g.svg")).isEmpty();
    }
}
