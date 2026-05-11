package de.mhus.vance.brain.tools;

import de.mhus.vance.toolpack.ToolInvocationContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Truncates oversized tool results before they go back to the LLM —
 * a 5 MB bash output would otherwise dominate the prompt and poison
 * cache for every subsequent turn. When a result's JSON size exceeds
 * {@code vance.tool.outputTruncation.threshold}, the original is
 * persisted to {@code ~/.vance/<tenantId>/<sessionId>/tool-results/
 * <uuid>.txt} and the LLM gets a stub map with the first 2 KB
 * preview, the original size, and the storage path.
 *
 * <p>Small results pass through unchanged. The threshold defaults to
 * 32 KB — large enough to keep typical tool returns intact, small
 * enough to keep a single oversized result from blowing the context
 * window.
 *
 * <p>v1 keeps everything pod-local. Multi-pod / S3 backing is in
 * {@code planning/brain-context-assembler.md} §7 listed as a future
 * step. The {@code sessionId}-segment in the path means a future
 * session-close cleanup can wipe an entire session's stash in one rm.
 *
 * <p>See {@code planning/brain-context-assembler.md} §7.
 */
@Service
@Slf4j
public class ToolResultStorage {

    /** Default truncation threshold (bytes). Matches plan §7. */
    public static final int DEFAULT_THRESHOLD_BYTES = 32 * 1024;

    /** Preview size (bytes) included in the stub. 2 KB per plan §7. */
    public static final int PREVIEW_BYTES = 2 * 1024;

    /** Meta-field keys on the stub map. Prefixed with {@code _} so they
     *  don't collide with caller-defined fields. */
    public static final String STUB_TRUNCATED_KEY = "_truncated";
    public static final String STUB_ORIGINAL_SIZE_KEY = "_originalSize";
    public static final String STUB_STORAGE_PATH_KEY = "_storagePath";
    public static final String STUB_PREVIEW_KEY = "_preview";
    public static final String STUB_MESSAGE_KEY = "_message";

    private final ObjectMapper objectMapper;
    private final Path baseDir;
    private final int thresholdBytes;

    public ToolResultStorage(
            ObjectMapper objectMapper,
            @Value("${vance.dir:#{systemProperties['user.home']}/.vance}") String baseDirSetting,
            @Value("${vance.tool.outputTruncation.threshold:" + DEFAULT_THRESHOLD_BYTES + "}")
                    int thresholdBytes) {
        this(objectMapper, Paths.get(baseDirSetting), thresholdBytes);
    }

    /** Test-friendly constructor — pass an absolute base path directly. */
    public ToolResultStorage(ObjectMapper objectMapper, Path baseDir, int thresholdBytes) {
        this.objectMapper = objectMapper;
        this.baseDir = baseDir;
        this.thresholdBytes = Math.max(1024, thresholdBytes);
    }

    /**
     * Returns the input unchanged when its JSON-serialized form fits
     * within the threshold; otherwise writes the original to disk and
     * returns a stub map. Best-effort: on disk-write failure the
     * original is returned verbatim — better a noisy LLM context than a
     * crashed turn.
     */
    public ToolResultPayload truncateIfLarge(
            @Nullable Map<String, Object> result,
            ToolInvocationContext ctx) {
        if (result == null || result.isEmpty()) {
            return new ToolResultPayload(
                    result == null ? Map.of() : result, false, 0L, null);
        }
        String serialized;
        try {
            serialized = objectMapper.writeValueAsString(result);
        } catch (RuntimeException e) {
            // Jackson v3 throws unchecked; non-serializable values are
            // rare here (tool results are Map<String,Object>). Pass
            // through on failure — the caller already had this map.
            log.debug("ToolResultStorage: skip — serialization failed: {}", e.toString());
            return new ToolResultPayload(result, false, 0L, null);
        }
        long size = serialized.getBytes(StandardCharsets.UTF_8).length;
        if (size <= thresholdBytes) {
            return new ToolResultPayload(result, false, size, null);
        }

        Path target;
        try {
            target = persist(serialized, ctx);
        } catch (IOException e) {
            // Disk full / permission error / SecurityManager. The LLM
            // turn still has to complete; passing the full result
            // through is loud but correct.
            log.warn("ToolResultStorage: persist failed, returning untruncated "
                            + "({} bytes): {}", size, e.toString());
            return new ToolResultPayload(result, false, size, null);
        }

        String preview = serialized.substring(0,
                Math.min(serialized.length(), PREVIEW_BYTES));
        Map<String, Object> stub = new LinkedHashMap<>();
        stub.put(STUB_TRUNCATED_KEY, true);
        stub.put(STUB_ORIGINAL_SIZE_KEY, size);
        stub.put(STUB_STORAGE_PATH_KEY, target.toAbsolutePath().toString());
        stub.put(STUB_PREVIEW_KEY, preview);
        stub.put(STUB_MESSAGE_KEY,
                "Tool result was " + size + " bytes — too large to inline. "
                        + "First " + PREVIEW_BYTES + " bytes in '_preview'; full "
                        + "content on disk at the path in '_storagePath'.");
        return new ToolResultPayload(stub, true, size, target.toAbsolutePath().toString());
    }

    /**
     * Writes {@code json} to a fresh file under the session-scoped
     * tool-results directory. Returns the absolute path. Creates
     * intermediate directories as needed.
     *
     * <p>Naming: random UUID. Collisions in the same session are
     * astronomically unlikely; we don't probe.
     */
    private Path persist(String json, ToolInvocationContext ctx) throws IOException {
        String tenant = sanitise(ctx.tenantId());
        String session = sanitise(ctx.sessionId() == null ? "_no_session" : ctx.sessionId());
        Path dir = baseDir
                .resolve(tenant)
                .resolve(session)
                .resolve("tool-results");
        Files.createDirectories(dir);
        Path target = dir.resolve(UUID.randomUUID().toString() + ".txt");
        Files.writeString(target, json, StandardCharsets.UTF_8);
        log.debug("ToolResultStorage: persisted {} bytes to {} (at={})",
                json.length(), target, Instant.now());
        return target;
    }

    /**
     * Sanitises a path segment so a malformed {@code tenantId} or
     * {@code sessionId} cannot escape the base directory via
     * {@code ../}. Pragmatic: collapse path separators + dots,
     * trim/empty → underscore. Real id strings (UUIDs, slugs) are
     * unaffected.
     */
    private static String sanitise(String s) {
        if (s == null || s.isBlank()) return "_";
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
