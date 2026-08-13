package de.mhus.vance.brain.trillian;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What a Trillian engine actually shows its model of an incoming
 * process-event.
 *
 * <p>The renderer used to emit only {@code sourceProcessId} and the
 * human summary while the prompt told the model "the payload carries
 * taskId and description". With no task id in view, the model reported
 * back the one identifier it could see — the source process id — and
 * every task correlation silently referred to the wrong thing. Nothing
 * failed; the ids just did not mean what they claimed.
 */
class TrillianEventRenderingTest {

    @Test
    void taskRequest_carriesTheTaskIdTheModelIsToldToUse() {
        String xml = TrillianUserEngine.renderForLlm(taskEvent("task-42", "Count the docs"));

        assertThat(xml).contains("taskId=\"task-42\"");
    }

    @Test
    void controlSeesTheTaskIdToo() {
        // Control correlates results back to what it queued, so it needs
        // the same identifier.
        String xml = TrillianControlEngine.renderForLlm(taskEvent("task-42", "Done"));

        assertThat(xml).contains("taskId=\"task-42\"");
    }

    @Test
    void eventWithoutATaskId_rendersWithoutTheAttribute() {
        SteerMessage.ProcessEvent plain = new SteerMessage.ProcessEvent(
                Instant.now(), null, "proc-1", ProcessEventType.SUMMARY,
                "something happened", null, null, null);

        String xml = TrillianUserEngine.renderForLlm(plain);

        // No invented empty attribute — absent means absent.
        assertThat(xml).doesNotContain("taskId").contains("sourceProcessId=\"proc-1\"");
    }

    @Test
    void sourceProcessIdIsStillThere() {
        String xml = TrillianUserEngine.renderForLlm(taskEvent("task-42", "Count the docs"));

        // It is the routing handle; adding taskId must not replace it.
        assertThat(xml).contains("sourceProcessId=\"proc-1\"").contains("Count the docs");
    }

    @Test
    void nonStringTaskId_isTreatedAsAbsentRatherThanStringified() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(TrillianInternalApi.PAYLOAD_KEY_TASK_ID, 42);
        SteerMessage.ProcessEvent odd = new SteerMessage.ProcessEvent(
                Instant.now(), null, "proc-1", ProcessEventType.SUMMARY,
                "summary", payload, null, null);

        assertThat(TrillianUserEngine.renderForLlm(odd)).doesNotContain("taskId");
    }

    private static SteerMessage.ProcessEvent taskEvent(String taskId, String summary) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(TrillianInternalApi.PAYLOAD_KEY_TASK_EVENT,
                TrillianInternalApi.TASK_EVENT_REQUEST);
        payload.put(TrillianInternalApi.PAYLOAD_KEY_TASK_ID, taskId);
        return new SteerMessage.ProcessEvent(
                Instant.now(), null, "proc-1", ProcessEventType.SUMMARY,
                summary, payload, null, null);
    }
}
