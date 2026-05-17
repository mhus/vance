package de.mhus.vance.brain.tools;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.toolpack.ToolInvocationContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/**
 * Truncation behaviour of {@link ToolResultStorage}: small results pass
 * through, large ones are persisted under
 * {@code <baseDir>/<tenant>/<session>/tool-results/} and replaced with
 * a stub map. Fail-open semantics on disk errors are exercised by
 * pointing the base dir at a read-only path.
 */
class ToolResultStorageTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ToolResultStorage storage;

    @BeforeEach
    void setUp() {
        // Low threshold so we can exercise the truncation path with
        // small fixture strings — production default is 32 KB.
        storage = new ToolResultStorage(objectMapper, tempDir, /*threshold*/ 1024);
    }

    @Test
    void truncateIfLarge_emptyResult_passesThrough_andReportsZeroSize() {
        ToolResultPayload p = storage.truncateIfLarge(Map.of(), ctx());

        assertThat(p.truncated()).isFalse();
        assertThat(p.originalSizeBytes()).isEqualTo(0L);
        assertThat(p.storagePath()).isNull();
    }

    @Test
    void truncateIfLarge_nullResult_returnsEmpty_andReportsZeroSize() {
        ToolResultPayload p = storage.truncateIfLarge(null, ctx());

        assertThat(p.truncated()).isFalse();
        assertThat(p.result()).isEmpty();
        assertThat(p.originalSizeBytes()).isEqualTo(0L);
    }

    @Test
    void truncateIfLarge_smallResult_passesThroughUnchanged() {
        Map<String, Object> result = Map.of("exitCode", 0, "stdout", "hello");

        ToolResultPayload p = storage.truncateIfLarge(result, ctx());

        assertThat(p.truncated()).isFalse();
        assertThat(p.result()).isSameAs(result);
        assertThat(p.originalSizeBytes()).isPositive();
        assertThat(p.storagePath()).isNull();
    }

    @Test
    void truncateIfLarge_largeResult_persistsToDiskAndReturnsStub() throws IOException {
        Map<String, Object> big = Map.of("stdout", "x".repeat(4096));

        ToolResultPayload p = storage.truncateIfLarge(big, ctx());

        assertThat(p.truncated()).isTrue();
        assertThat(p.originalSizeBytes()).isGreaterThan(1024L);
        assertThat(p.storagePath()).isNotNull();

        // The stub map carries the meta-fields the LLM will see.
        Map<String, Object> stub = p.result();
        assertThat(stub).containsKey(ToolResultStorage.STUB_TRUNCATED_KEY)
                        .containsKey(ToolResultStorage.STUB_ORIGINAL_SIZE_KEY)
                        .containsKey(ToolResultStorage.STUB_RESULT_ID_KEY)
                        .containsKey(ToolResultStorage.STUB_PREVIEW_KEY)
                        .containsKey(ToolResultStorage.STUB_MESSAGE_KEY);
        assertThat(stub.get(ToolResultStorage.STUB_TRUNCATED_KEY)).isEqualTo(true);
        // The stub never leaks the absolute disk path — only the
        // bare resultId — but the on-disk filename derives from it.
        String resultId = (String) stub.get(ToolResultStorage.STUB_RESULT_ID_KEY);
        assertThat(resultId).isNotNull().doesNotContain("/").doesNotContain(".txt");
        assertThat(p.storagePath()).endsWith(resultId + ".txt");
        // The _message must tell the LLM about tool_result_read so it
        // doesn't fall back to guessing (the historical 'scratch_read'
        // failure mode) — pin the wording so regressions fail loudly.
        assertThat(stub.get(ToolResultStorage.STUB_MESSAGE_KEY).toString())
                .contains("tool_result_read")
                .contains(resultId);

        // Disk contents must be the original serialized JSON.
        Path written = Path.of(p.storagePath());
        assertThat(Files.exists(written)).isTrue();
        String disk = Files.readString(written, StandardCharsets.UTF_8);
        assertThat(disk).contains("xxxxxx").contains("stdout");
    }

    @Test
    void read_roundTripsPersistedJson() throws IOException {
        // Write a result via the truncation path, then pull it back
        // through the new read() entry point — the two must be
        // bytewise identical (the LLM gets exactly what the inline
        // form would have shown if it had fit).
        Map<String, Object> big = Map.of("stdout", "x".repeat(4096));
        ToolResultPayload p = storage.truncateIfLarge(big, ctx());
        String resultId = (String) p.result().get(ToolResultStorage.STUB_RESULT_ID_KEY);

        String content = storage.read(resultId, ctx());

        assertThat(content).contains("xxxxxx").contains("stdout");
        // The persisted on-disk file is the source of truth.
        Path written = Path.of(p.storagePath());
        assertThat(content).isEqualTo(Files.readString(written, StandardCharsets.UTF_8));
    }

    @Test
    void read_rejectsBlankAndNullIds() {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> storage.read("", ctx()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("required");
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> storage.read(null, ctx()))
                .isInstanceOf(IOException.class);
    }

    @Test
    void read_rejectsPathTraversalAttempts() {
        // Even though sanitise() collapses '..' segments, the read
        // path explicitly compares the post-sanitised id with the
        // input. A '../' attempt mutates under sanitisation and is
        // rejected up front rather than silently rewriting.
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> storage.read("../etc/passwd", ctx()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("illegal");
    }

    @Test
    void read_returns_ioException_when_idDoesNotExist() {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> storage.read(
                        java.util.UUID.randomUUID().toString(), ctx()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void read_isScopedToCallerSession() throws IOException {
        // Persist a result under session-A, then try to read it
        // through a context that names session-B. The id is valid,
        // but the resolved path lives outside session-B's
        // tool-results dir → IOException.
        Map<String, Object> big = Map.of("stdout", "x".repeat(4096));
        ToolResultPayload p = storage.truncateIfLarge(big, ctx("acme", "sess-A"));
        String resultId = (String) p.result().get(ToolResultStorage.STUB_RESULT_ID_KEY);

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> storage.read(resultId, ctx("acme", "sess-B")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void truncateIfLarge_storesUnderTenantSessionToolResultsLayout() {
        Map<String, Object> big = Map.of("stdout", "x".repeat(4096));

        ToolResultPayload p = storage.truncateIfLarge(big, ctx("acme", "sess-42"));

        Path written = Path.of(p.storagePath());
        // ~/.vance/acme/sess-42/tool-results/<uuid>.txt
        assertThat(written.toString()).contains("acme")
                                       .contains("sess-42")
                                       .contains("tool-results")
                                       .endsWith(".txt");
    }

    @Test
    void truncateIfLarge_sanitisesPathTraversalAttempts() throws IOException {
        Map<String, Object> big = Map.of("stdout", "x".repeat(4096));

        ToolResultPayload p = storage.truncateIfLarge(
                big, new ToolInvocationContext("../etc", "p", "../../bad", "proc", "u"));

        Path written = Path.of(p.storagePath());
        // No escaped segments — `..` should not survive sanitisation.
        assertThat(written.startsWith(tempDir.toAbsolutePath())).isTrue();
        assertThat(written.toString()).doesNotContain("../");
    }

    @Test
    void truncateIfLarge_nullSessionId_usesPlaceholderSegment() {
        Map<String, Object> big = Map.of("stdout", "x".repeat(4096));

        ToolResultPayload p = storage.truncateIfLarge(
                big, new ToolInvocationContext("acme", "p", null, "proc", "u"));

        // Should write under a placeholder segment, not crash.
        assertThat(p.truncated()).isTrue();
        assertThat(Path.of(p.storagePath()).startsWith(tempDir.toAbsolutePath())).isTrue();
    }

    @Test
    void truncateIfLarge_previewBoundedTo2KB() {
        // Build a payload whose `stdout` field alone exceeds the 2 KB
        // preview cap several times over.
        char[] big = new char[8 * 1024];
        Arrays.fill(big, 'A');
        Map<String, Object> result = Map.of("stdout", new String(big));

        ToolResultPayload p = storage.truncateIfLarge(result, ctx());

        String preview = (String) p.result().get(ToolResultStorage.STUB_PREVIEW_KEY);
        assertThat(preview).isNotNull();
        assertThat(preview.length()).isLessThanOrEqualTo(ToolResultStorage.PREVIEW_BYTES);
    }

    @Test
    void truncateIfLarge_thresholdBelowMinimum_clampedTo1KB() {
        // Sanity: a hostile config of threshold=0 would always truncate
        // and produce noise. The implementation clamps to 1 KB minimum.
        ToolResultStorage tiny = new ToolResultStorage(
                objectMapper, tempDir, /*threshold*/ 0);
        // Force a result UNDER the 1 KB clamp — small map.
        Map<String, Object> small = Map.of("k", "v");

        ToolResultPayload p = tiny.truncateIfLarge(small, ctx());

        assertThat(p.truncated()).isFalse();
    }

    @Test
    void truncateIfLarge_nonSerializableField_failsOpen() {
        // A value Jackson can't serialise (cyclic ref). Service must
        // not throw — it returns the original verbatim and logs.
        Map<String, Object> cyclic = new LinkedHashMap<>();
        List<Object> list = new java.util.ArrayList<>();
        list.add(list); // self-reference → JsonMappingException on write
        cyclic.put("loop", list);

        ToolResultPayload p = storage.truncateIfLarge(cyclic, ctx());

        assertThat(p.truncated()).isFalse();
        assertThat(p.result()).isSameAs(cyclic);
    }

    // ─── helpers ────────────────────────────────────────────────────────

    private static ToolInvocationContext ctx() {
        return ctx("acme", "sess");
    }

    private static ToolInvocationContext ctx(String tenant, String session) {
        return new ToolInvocationContext(tenant, "proj", session, "proc", "u");
    }
}
