package de.mhus.vance.brain.tools.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.inbox.Criticality;
import de.mhus.vance.api.inbox.MaximegalonStatus;
import de.mhus.vance.api.inbox.MaximegalonType;
import de.mhus.vance.shared.inbox.MaximegalonDocument;
import de.mhus.vance.shared.inbox.MaximegalonMessage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The shaping layer. Everything here is a rule that fails silently if it
 * regresses: a leaked effect type is an invitation, an uncollapsed title
 * reshapes every line around it, and a truncation with no marker reads to a
 * model as "that is all there is".
 */
class InboxRowsTest {

    private static MaximegalonDocument.MaximegalonDocumentBuilder thread() {
        return MaximegalonDocument.builder()
                .id("t1")
                .tenantId("acme")
                .type(MaximegalonType.APPROVAL)
                .status(MaximegalonStatus.PENDING)
                .criticality(Criticality.NORMAL)
                .assignedToUserId("wile.coyote")
                .originatorUserId("road.runner")
                .title("Deploy?")
                .requiresAction(true);
    }

    private static MaximegalonMessage message(String id, String body) {
        return MaximegalonMessage.builder()
                .id(id).authorUserId("road.runner").body(body).build();
    }

    @Test
    void thread_effectType_isReducedToABoolean() {
        // The type name of a server effect is, to a model, an invitation to
        // trigger it — and there is no tool that could.
        Map<String, Object> out = InboxRows.thread(
                thread().effectType("permission-request").build(), List.of(), 0, 0);

        assertThat(out.get("hasEffect")).isEqualTo(true);
        assertThat(out).doesNotContainKey("effectType");
        assertThat(out).doesNotContainKey("effectRef");
    }

    @Test
    void thread_reactionsAndUnreadFor_areNotShipped() {
        MaximegalonDocument doc = thread().build();
        doc.setUnreadFor(new java.util.ArrayList<>(List.of("wile.coyote")));

        Map<String, Object> out = InboxRows.thread(doc, List.of(), 0, 0);

        assertThat(out).doesNotContainKey("unreadFor");
        assertThat(out).doesNotContainKey("reactions");
    }

    @Test
    void thread_omittedMessages_reportsWhatWasLeftBehind() {
        List<MaximegalonMessage> page = List.of(message("m1", "a"), message("m2", "b"));

        Map<String, Object> out = InboxRows.thread(thread().build(), page, 10, 40);

        assertThat(out.get("messageCount")).isEqualTo(40);
        assertThat(out.get("messageOffset")).isEqualTo(10);
        assertThat(out.get("omittedMessages")).isEqualTo(28);
    }

    @Test
    void thread_payloadKeyNamedTitle_doesNotOverwriteTheCollapsedTitle() {
        // The exact bug found in the Zarniwoop hit rows: canonical fields have
        // to be written after the foreign map, or raw text wins.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", "raw\n\nfrom payload");

        Map<String, Object> out = InboxRows.thread(
                thread().title("Deploy?").payload(payload).build(), List.of(), 0, 0);

        assertThat(out.get("title")).isEqualTo("Deploy?");
    }

    @Test
    void thread_longPayloadValue_isReplacedByItsSize() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("proposed", "x".repeat(5000));

        Map<String, Object> out = InboxRows.thread(
                thread().payload(payload).build(), List.of(), 0, 0);

        @SuppressWarnings("unchecked")
        Map<String, Object> shaped = (Map<String, Object>) out.get("payload");
        assertThat((String) shaped.get("proposed")).startsWith("…").contains("5000");
    }

    @Test
    void clamp_collapsesWhitespaceAndMarksTheCut() {
        String body = "line one\n\n\nline two " + "y".repeat(6000);

        Map<String, Object> out = InboxRows.thread(
                thread().body(body).build(), List.of(), 0, 0);

        String shaped = (String) out.get("body");
        assertThat(shaped).doesNotContain("\n").endsWith("…");
        assertThat(shaped.length()).isLessThanOrEqualTo(4001);
    }

    @Test
    void listRow_carriesUnreadAndMessageCount_butNoBody() {
        Map<String, Object> row = InboxRows.listRow(
                thread().body("the long question").build(), true, 3);

        assertThat(row.get("unread")).isEqualTo(true);
        assertThat(row.get("messageCount")).isEqualTo(3);
        assertThat(row.get("requiresAction")).isEqualTo(true);
        assertThat(row).doesNotContainKey("body");
        assertThat(row).doesNotContainKey("payload");
    }

    @Test
    void listRow_unknownMessageCount_isOmittedRatherThanZero() {
        Map<String, Object> row = InboxRows.listRow(thread().build(), false, null);

        assertThat(row).doesNotContainKey("messageCount");
    }
}
