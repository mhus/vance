package de.mhus.vance.brain.tools;

import java.util.Map;

/**
 * Result of a {@link ToolResultStorage#truncateIfLarge} call. Either:
 *
 * <ul>
 *   <li><b>Not truncated</b>: {@code result} is the caller's original
 *       map, {@code truncated} is {@code false}, {@code storagePath} is
 *       {@code null}.</li>
 *   <li><b>Truncated</b>: {@code result} is a stub map with meta-fields
 *       ({@code _truncated}, {@code _originalSize}, {@code _storagePath},
 *       {@code _preview}, {@code _message}) suitable for handing back to
 *       the LLM. {@code storagePath} is the absolute path to the
 *       on-disk copy of the original serialized JSON. The LLM sees a
 *       small surface; the full content is preserved for human inspection
 *       and for a future "read_tool_result" tool.</li>
 * </ul>
 *
 * <p>{@code originalSizeBytes} reports the byte length of the JSON-
 * serialized original payload, regardless of whether truncation
 * happened. Allows callers to log and instrument size distributions
 * without re-measuring.
 */
public record ToolResultPayload(
        Map<String, Object> result,
        boolean truncated,
        long originalSizeBytes,
        java.lang.@org.jspecify.annotations.Nullable String storagePath) {
}
