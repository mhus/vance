package de.mhus.vance.addon.brain.bistromath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.toolpack.ToolException;
import org.junit.jupiter.api.Test;

/**
 * The {@code agent:} flag on a widget: may the chat beside the app trigger this
 * action.
 *
 * <p>Its own test class because it is the one field in a view where being wrong
 * in the permissive direction hands an agent a button nobody meant to give it.
 * Read by default, act by declaration — so what is tested here is mostly what
 * the parser <em>refuses</em>.
 */
class AgentFlagTest {

    @Test
    void agent_defaultsToDeny() {
        ViewNode node = ViewParser.parse(
                "type: button\nid: save\nlabel: Save\non:\n  click: reload\n", "v.yaml");

        assertThat(node.agent()).isFalse();
    }

    @Test
    void agent_isTakenWhenWritten() {
        ViewNode node = ViewParser.parse(
                "type: button\nid: save\nlabel: Save\non:\n  click: reload\nagent: true\n", "v.yaml");

        assertThat(node.agent()).isTrue();
    }

    /**
     * A string is refused rather than coerced. YAML happily reads
     * {@code agent: yes-please} as text, and a truthy check would then grant
     * the permission the author did not spell.
     */
    @Test
    void agent_refusesAnythingButABoolean() {
        assertThatThrownBy(() -> ViewParser.parse(
                "type: button\nid: save\nlabel: Save\non:\n  click: reload\nagent: yes-please\n", "v.yaml"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("`agent` is true or false");
    }

    @Test
    void agent_withoutAnActionIsRejected() {
        // Not harmless noise: it reads as a granted permission, so the author
        // believes an agent can drive a widget that has nothing to trigger.
        assertThatThrownBy(() -> ViewParser.parse(
                "type: text\nid: hint\ntext: nothing to press\nagent: true\n", "v.yaml"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("needs an action to trigger");
    }

    @Test
    void agent_falseIsAllowedAndExplicit() {
        // Writing the default is legitimate: it says "deliberately closed"
        // rather than "not thought about".
        ViewNode node = ViewParser.parse(
                "type: button\nid: save\nlabel: Save\non:\n  click: reload\nagent: false\n", "v.yaml");

        assertThat(node.agent()).isFalse();
    }
}
