package de.mhus.vance.brain.template;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.shared.magrathea.MagratheaWorkflowLoader;
import de.mhus.vance.shared.magrathea.ResolvedMagratheaWorkflow;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The bundled workflow templates for mail, Slack and loops: every one renders, for each choice of its switches, to a
 * workflow the real loader accepts — states that exist, a loop whose body is closed, a start that is declared.
 */
class BundledMailSlackLoopTemplatesTest {

    private final PromptTemplateRenderer renderer = new PromptTemplateRenderer();

    @Test
    void mailTriage_rendersForBothChoicesOfMarkSeen() {
        ResolvedMagratheaWorkflow marking = parse("mail-triage", mailTriage(true));
        ResolvedMagratheaWorkflow plain = parse("mail-triage", mailTriage(false));

        assertThat(marking.startState()).isEqualTo("list");
        assertThat(marking.states()).containsKeys("list", "each", "read", "rate", "notify", "mark", "next", "done");
        // Without the mark there is no state for it, and nothing leads there.
        assertThat(plain.states()).doesNotContainKey("mark");
        assertThat(rendered("workflow-mail-triage", mailTriage(false)))
                .doesNotContain("set_seen")
                .doesNotContain("\n  mark:")
                .contains("normal: next");
        assertThat(rendered("workflow-mail-triage", mailTriage(true)))
                .contains("tool: sales__list_messages")
                .contains("tool: sales__set_seen")
                .contains("targetUserId: \"mike\"")
                .doesNotContain("{{");
    }

    @Test
    void mailReply_rendersForBothChoicesOfMarkSeen() {
        ResolvedMagratheaWorkflow marking = parse("mail-reply", mailReply(true));
        ResolvedMagratheaWorkflow plain = parse("mail-reply", mailReply(false));

        assertThat(marking.states()).containsKeys("list", "each", "read", "draft", "review", "send", "mark", "next");
        assertThat(plain.states()).doesNotContainKey("mark");
        // The reply is sent only after the approval gate.
        assertThat(rendered("workflow-mail-reply", mailReply(true)))
                .contains("tool: noreply__send_message")
                .contains("approved: send")
                .doesNotContain("{{");
    }

    @Test
    void mailSend_rendersWithAndWithoutApproval() {
        ResolvedMagratheaWorkflow asking = parse("mail-send", mailSend(true));
        ResolvedMagratheaWorkflow direct = parse("mail-send", mailSend(false));

        assertThat(asking.startState()).isEqualTo("write");
        assertThat(asking.states()).containsKeys("write", "review", "send", "done");
        assertThat(direct.states()).doesNotContainKey("review");
        assertThat(rendered("workflow-mail-send", mailSend(false)))
                .contains("success: send")
                .doesNotContain("{{");
    }

    @Test
    void slackDigest_keepsTheSecretReferenceForTheRunToResolve() {
        ResolvedMagratheaWorkflow workflow = parse("slack-digest", slackDigest());

        assertThat(workflow.startState()).isEqualTo("fetch");
        assertThat(workflow.states()).containsKeys("fetch", "summarise", "post", "done", "failed");
        // The secret reference is meant for the tool call at run time; rendering must leave it as it is.
        assertThat(rendered("workflow-slack-digest", slackDigest()))
                .contains("{{secret:user:oauth.slack.access_token}}")
                .contains("tool: slack_rest__conversations_history")
                .contains("channel: \"C0123456789\"");
    }

    @Test
    void forEachItem_rendersALoopWhoseBodyIsClosedByAnEnd() {
        ResolvedMagratheaWorkflow workflow = parse("for-each-item", forEachItem());

        assertThat(workflow.startState()).isEqualTo("loop");
        assertThat(workflow.states()).containsKeys("loop", "work", "next", "done", "failed");
        assertThat(rendered("workflow-for-each-item", forEachItem()))
                .contains("collect: \"${state.result}\"")
                .contains("maxItems: 50")
                .contains("[\"first\", \"second\", \"third\"]");
    }

    @Test
    void forEachItem_withoutASampleListStillParses() {
        Map<String, Object> values = new java.util.HashMap<>(forEachItem());
        values.put("sampleItems", "");

        assertThat(parse("for-each-item", values).startState()).isEqualTo("loop");
    }

    @Test
    void everyRenderedTemplateCarriesTheKindHeader() {
        assertThat(rendered("workflow-mail-triage", mailTriage(true))).contains("kind: vance-workflow");
        assertThat(rendered("workflow-mail-reply", mailReply(true))).contains("kind: vance-workflow");
        assertThat(rendered("workflow-mail-send", mailSend(true))).contains("kind: vance-workflow");
        assertThat(rendered("workflow-slack-digest", slackDigest())).contains("kind: vance-workflow");
        assertThat(rendered("workflow-for-each-item", forEachItem())).contains("kind: vance-workflow");
    }

    // ──────────── what the form hands over ────────────

    private static Map<String, Object> mailTriage(boolean markSeen) {
        return Map.of(
                "pack", "sales",
                "criteria", "outages and payment problems",
                "notifyUser", "mike",
                "markSeen", markSeen,
                "limit", 20,
                "recipe", "ford",
                "name", "mail-triage");
    }

    private static Map<String, Object> mailReply(boolean markSeen) {
        return Map.of(
                "imapPack", "support",
                "smtpPack", "noreply",
                "instructions", "friendly and short",
                "markSeen", markSeen,
                "limit", 5,
                "recipe", "ford",
                "name", "mail-reply");
    }

    private static Map<String, Object> mailSend(boolean approve) {
        return Map.of(
                "smtpPack", "noreply",
                "to", "boss@example.com",
                "subject", "Weekly report",
                "instruction", "Summarise the week",
                "approve", approve,
                "recipe", "ford",
                "name", "mail-send");
    }

    private static Map<String, Object> slackDigest() {
        return Map.of(
                "pack", "slack_rest",
                "channel", "C0123456789",
                "notifyUser", "mike",
                "focus", "decisions",
                "limit", 100,
                "recipe", "ford",
                "name", "slack-digest");
    }

    private static Map<String, Object> forEachItem() {
        return Map.of(
                "task", "Summarise the element",
                "sampleItems", "[\"first\", \"second\", \"third\"]",
                "maxItems", 50,
                "recipe", "ford",
                "name", "for-each-item");
    }

    // ──────────── helpers ────────────

    private ResolvedMagratheaWorkflow parse(String name, Map<String, Object> values) {
        return MagratheaWorkflowLoader.parseYaml(name, rendered("workflow-" + name, values));
    }

    private String rendered(String template, Map<String, Object> values) {
        return renderer.renderStructured(body(template), values);
    }

    private static String body(String template) {
        String resource = "vance-defaults/_vance/templates/" + template + ".tmpl.yaml";
        try (InputStream in =
                BundledMailSlackLoopTemplatesTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("bundled template body missing: " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + resource, e);
        }
    }
}
