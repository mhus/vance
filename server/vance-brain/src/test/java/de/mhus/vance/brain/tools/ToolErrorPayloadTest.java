package de.mhus.vance.brain.tools;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.toolpack.ToolException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for the tool-failure payload handed to the model.
 *
 * <p>What is being pinned here is not "some JSON comes out" but the
 * two properties that made a failed edit look like a success: the
 * payload must be structurally marked as a failure ({@code ok:false}),
 * and the failure has to come before any recovery advice.
 */
class ToolErrorPayloadTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void payload_marksFailureStructurallyAndInTheText() {
        String json = ToolErrorPayload.json(mapper, "Edit failed: no such file");

        assertThat(json).contains("\"ok\":false");
        assertThat(json).contains(ToolErrorPayload.FAILURE_PREFIX);
        assertThat(json).contains("Edit failed: no such file");
    }

    @Test
    void payload_putsFailureBeforeHint() {
        String json = ToolErrorPayload.json(
                mapper, "oldText not found", "expand surrounding context");

        assertThat(json.indexOf("\"ok\"")).isLessThan(json.indexOf("\"error\""));
        assertThat(json.indexOf("\"error\"")).isLessThan(json.indexOf("\"hint\""));
    }

    @Test
    void payload_omitsHintFieldWhenThereIsNoHint() {
        assertThat(ToolErrorPayload.json(mapper, "boom", "  ")).doesNotContain("hint");
        assertThat(ToolErrorPayload.json(mapper, "boom")).doesNotContain("hint");
    }

    @Test
    void payload_fromToolException_carriesItsHint() {
        ToolException e = new ToolException(
                "Client tool 'client_file_edit' failed", "file missing = file_read first", null);

        String json = ToolErrorPayload.json(mapper, e);

        assertThat(json).contains("\"hint\":\"file missing = file_read first\"");
        assertThat(json).contains(ToolErrorPayload.FAILURE_PREFIX
                + "Client tool 'client_file_edit' failed");
    }

    @Test
    void payload_blankMessage_stillStatesThatTheCallFailed() {
        String json = ToolErrorPayload.json(mapper, "");

        assertThat(json).contains(ToolErrorPayload.FAILURE_PREFIX);
        assertThat(json).contains("no reason");
    }

    @Test
    void map_keepsOkFirstSoSkimmingModelsSeeItImmediately() {
        assertThat(ToolErrorPayload.map("boom", "hint").keySet())
                .containsExactly("ok", "error", "hint");
    }
}
