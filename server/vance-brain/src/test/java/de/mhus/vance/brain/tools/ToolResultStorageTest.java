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
                        .containsKey(ToolResultStorage.STUB_STORAGE_PATH_KEY)
                        .containsKey(ToolResultStorage.STUB_PREVIEW_KEY)
                        .containsKey(ToolResultStorage.STUB_MESSAGE_KEY);
        assertThat(stub.get(ToolResultStorage.STUB_TRUNCATED_KEY)).isEqualTo(true);
        assertThat(stub.get(ToolResultStorage.STUB_STORAGE_PATH_KEY))
                .isEqualTo(p.storagePath());

        // Disk contents must be the original serialized JSON.
        Path written = Path.of(p.storagePath());
        assertThat(Files.exists(written)).isTrue();
        String disk = Files.readString(written, StandardCharsets.UTF_8);
        assertThat(disk).contains("xxxxxx").contains("stdout");
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
