package de.mhus.vance.api.insights;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Read-only view of a single entry in a process's pending queue.
 *
 * <p>{@code type} is the steer-message kind as a string —
 * {@code USER_CHAT_INPUT}, {@code PROCESS_EVENT}, {@code TOOL_RESULT},
 * {@code INBOX_ANSWER}, etc. The {@code payload} carries kind-specific
 * fields (text, source-process-id, tool-call-id, ...).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("insights")
public class PendingMessageInsightsDto {

    private String type;

    private @Nullable Instant at;

    private @Nullable String fromUser;

    @Builder.Default
    private Map<String, Object> payload = new LinkedHashMap<>();
}
