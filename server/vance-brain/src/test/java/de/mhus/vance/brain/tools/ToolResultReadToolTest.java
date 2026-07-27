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
                .containsEntry("offset", 0)
                .containsEntry("hasMore", false)
                .containsKey("content")
                .containsKey("returnedChars")
                .containsKey("totalChars");
        assertThat((String) out.get("content"))
                .contains("xxxxxx")
                .contains("stdout");
        assertThat(out.get("returnedChars")).isEqualTo(((String) out.get("content")).length());
        assertThat(out.get("totalChars")).isEqualTo(out.get("returnedChars"));
    }

    @Test
    void bypassOutputTruncation_isTrue() {
        // The regress guard: tool_result_read's own output must NOT be
        // re-fed through the truncation path — otherwise reading a large
        // stored result persists a fresh stub and loops forever
        // (observed live 2026-07-27 on the utaw.tech article fetch).
        assertThat(tool.bypassOutputTruncation()).isTrue();
    }

    @Test
    void invoke_largeResult_paginatesWithNextOffset() {
        // Content bigger than one window comes back in bounded chunks;
        // the LLM pages forward via nextOffset instead of re-reading.
        String payload = "abcdefghij".repeat(20_000); // 200k chars of content
        Map<String, Object> big = Map.of("stdout", payload);
        ToolResultPayload p = storage.truncateIfLarge(big, CTX);
        String resultId = (String) p.result().get(ToolResultStorage.STUB_RESULT_ID_KEY);

        Map<String, Object> first = tool.invoke(
                Map.of("id", resultId, "maxChars", 1000), CTX);

        assertThat(first).containsEntry("offset", 0).containsEntry("hasMore", true);
        assertThat((String) first.get("content")).hasSize(1000);
        int next = (int) first.get("nextOffset");
        assertThat(next).isEqualTo(1000);
        int total = (int) first.get("totalChars");

        Map<String, Object> second = tool.invoke(
                Map.of("id", resultId, "offset", next, "maxChars", 1000), CTX);
        assertThat(second).containsEntry("offset", 1000);
        assertThat((int) second.get("totalChars")).isEqualTo(total);

        // The two adjacent windows must reconstruct the single [0,2000)
        // window exactly — proves offset paging is contiguous, no gap
        // and no overlap.
        Map<String, Object> whole = tool.invoke(
                Map.of("id", resultId, "maxChars", 2000), CTX);
        assertThat((String) first.get("content") + (String) second.get("content"))
                .isEqualTo((String) whole.get("content"));
    }

    @Test
    void invoke_maxChars_cappedAtHardCeiling() {
        String payload = "y".repeat(MaxProbe.OVER); // bigger than the ceiling
        Map<String, Object> big = Map.of("stdout", payload);
        ToolResultPayload p = storage.truncateIfLarge(big, CTX);
        String resultId = (String) p.result().get(ToolResultStorage.STUB_RESULT_ID_KEY);

        Map<String, Object> out = tool.invoke(
                Map.of("id", resultId, "maxChars", Integer.MAX_VALUE), CTX);

        assertThat((int) out.get("returnedChars"))
                .isLessThanOrEqualTo(ToolResultReadTool.MAX_WINDOW_CHARS);
        assertThat(out).containsEntry("hasMore", true);
    }

    @Test
    void invoke_offsetPastEnd_returnsEmptyWindow() {
        Map<String, Object> big = Map.of("stdout", "x".repeat(4096));
        ToolResultPayload p = storage.truncateIfLarge(big, CTX);
        String resultId = (String) p.result().get(ToolResultStorage.STUB_RESULT_ID_KEY);

        Map<String, Object> out = tool.invoke(
                Map.of("id", resultId, "offset", 10_000_000), CTX);

        assertThat((String) out.get("content")).isEmpty();
        assertThat(out).containsEntry("hasMore", false);
    }

    @Test
    void invoke_negativeOffset_throwsToolException() {
        Map<String, Object> big = Map.of("stdout", "x".repeat(4096));
        ToolResultPayload p = storage.truncateIfLarge(big, CTX);
        String resultId = (String) p.result().get(ToolResultStorage.STUB_RESULT_ID_KEY);

        assertThatThrownBy(() -> tool.invoke(Map.of("id", resultId, "offset", -1), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("offset");
    }

    /** Marker constant kept out of the assertion for readability. */
    private static final class MaxProbe {
        static final int OVER = ToolResultReadTool.MAX_WINDOW_CHARS + 10_000;
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
