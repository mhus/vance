package de.mhus.vance.api.llmtrace;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One row of the persistent LLM-trace log. Mirrors
 * {@code LlmTraceDocument} field-for-field; the {@code direction} is
 * carried as a lower-case string ({@code input | output | tool_call |
 * tool_result}) so the UI can switch on it without importing the
 * server-side enum.
 *
 * <p>The Insights UI groups rows by {@link #turnId} and renders them
 * in {@link #sequence} order — one round-trip = one collapsible block.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("llmtrace")
public class LlmTraceDto {

    private @Nullable String id;

    private String tenantId = "";
    private @Nullable String projectId;
    private @Nullable String sessionId;
    private String processId = "";

    private @Nullable String engine;
    private @Nullable String turnId;
    private int sequence;

    /**
     * Lower-case kind: {@code input}, {@code output}, {@code tool_call},
     * {@code tool_result}.
     */
    private String direction = "";

    private @Nullable String role;
    private @Nullable String content;

    private @Nullable String toolName;
    private @Nullable String toolCallId;

    private @Nullable String modelAlias;
    private @Nullable String providerModel;

    private @Nullable Integer tokensIn;
    private @Nullable Integer tokensOut;
    /** Tokens written to the Anthropic prompt cache (~1.25× input price). */
    private @Nullable Integer cacheCreationInputTokens;
    /** Tokens read from the prompt cache (~10% input price). */
    private @Nullable Integer cacheReadInputTokens;
    private @Nullable Long elapsedMs;

    private @Nullable Instant createdAt;
}
