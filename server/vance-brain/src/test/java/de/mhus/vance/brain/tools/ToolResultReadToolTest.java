package de.mhus.vance.brain.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/**
 * Surface-level tests for {@link ToolResultReadTool}. Round-trip
 * coverage (write via {@code truncateIfLarge}, read back via the
 * tool) is the headline scenario — that's the exact path Ford
 * hits when a {@code web_fetch} response gets truncated and the
 * LLM follows up to load the rest.
 */
class ToolResultReadToolTest {

    private static final ToolInvocationContext CTX =
            new ToolInvocationContext("acme", "proj", "sess", "proc", "user");

    @TempDir
    Path tempDir;

    private ToolResultStorage storage;
    private ToolResultReadTool tool;

    @BeforeEach
    void setUp() {
        storage = new ToolResultStorage(new ObjectMapper(), tempDir, /*threshold*/ 1024);
        tool = new ToolResultReadTool(storage);
    }

    @Test
    void invoke_roundTripsPersistedResult() {
        Map<String, Object> big = Map.of("stdout", "x".repeat(4096));
        ToolResultPayload p = storage.truncateIfLarge(big, CTX);
        String resultId = (String) p.result().get(ToolResultStorage.STUB_RESULT_ID_KEY);

        Map<String, Object> out = tool.invoke(Map.of("id", resultId), CTX);

        assertThat(out)
                .containsEntry("id", resultId)
                .containsKey("content")
                .containsKey("length");
        assertThat((String) out.get("content"))
                .contains("xxxxxx")
                .contains("stdout");
        assertThat(out.get("length")).isEqualTo(((String) out.get("content")).length());
    }

    @Test
    void invoke_blankId_throwsToolException() {
        assertThatThrownBy(() -> tool.invoke(Map.of("id", ""), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("'id' is required");
    }

    @Test
    void invoke_missingId_throwsToolException() {
        assertThatThrownBy(() -> tool.invoke(Map.of(), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("'id' is required");
    }

    @Test
    void invoke_unknownId_wraps_ioException_as_toolException() {
        assertThatThrownBy(() -> tool.invoke(
                Map.of("id", java.util.UUID.randomUUID().toString()), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void invoke_trimsWhitespaceAroundId() {
        // LLMs sometimes copy the id with surrounding quotes or
        // whitespace — accept and trim. The stub always emits a
        // clean UUID, but we want the tool to be forgiving.
        Map<String, Object> big = Map.of("stdout", "x".repeat(4096));
        ToolResultPayload p = storage.truncateIfLarge(big, CTX);
        String resultId = (String) p.result().get(ToolResultStorage.STUB_RESULT_ID_KEY);

        Map<String, Object> out = tool.invoke(Map.of("id", "  " + resultId + "  "), CTX);

        assertThat(out).containsEntry("id", resultId);
    }

    @Test
    void labels_marked_readOnly() {
        // tool_result_read survives EXPLORING/PLANNING-mode label
        // strips precisely because it carries no write-side effect.
        // Pin the label so a future refactor can't quietly demote it.
        assertThat(tool.labels()).contains("read-only");
    }

    @Test
    void name_is_stable() {
        // Referenced in ToolResultStorage's _message wording verbatim;
        // a rename here breaks the LLM's only documented path to the
        // truncated content.
        assertThat(tool.name()).isEqualTo("tool_result_read");
    }
}
