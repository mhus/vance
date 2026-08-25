package de.mhus.vance.addon.brain.bistromath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.toolpack.ToolException;
import org.junit.jupiter.api.Test;

/**
 * The `html` widget: sanitised markup, passed through unchanged.
 *
 * <p>It exists beside `markdown` rather than as a flag on it because Markdown
 * restructures HTML — a blank line inside a block element gets a {@code <p>} the
 * author never wrote. Measured in the browser, not assumed.
 */
class HtmlWidgetTest {

    @Test
    void html_takesALiteral() {
        ViewNode node = ViewParser.parse("type: html\ntext: <b>hi</b>\n", "v.yaml");

        assertThat(node.type()).isEqualTo("html");
        assertThat(node.text()).isEqualTo("<b>hi</b>");
    }

    @Test
    void html_takesAStateKey() {
        ViewNode node = ViewParser.parse("type: html\nfrom: markup\n", "v.yaml");

        assertThat(node.from()).isEqualTo("markup");
    }

    @Test
    void html_withNeitherIsRejected() {
        // Same rule as text and markdown: a widget showing nothing is
        // indistinguishable from a layout mistake in a generic renderer.
        assertThatThrownBy(() -> ViewParser.parse("type: html\nid: x\n", "v.yaml"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("needs `text` (a literal) or `from` (a state key)");
    }

    @Test
    void html_isNotReportedAsReservedOrUnknown() {
        // The two failure modes for a widget name that is not wired up: the
        // parser calls it unknown, or the inventory calls it planned. Neither
        // may happen for a widget that renders.
        ViewNode node = ViewParser.parse("type: html\ntext: x\n", "v.yaml");

        assertThat(WidgetType.PLANNED).doesNotContain("html");
        assertThat(node.type()).isEqualTo("html");
    }
}
