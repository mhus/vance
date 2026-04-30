package de.mhus.vance.api.llmtrace;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Paginated response for the LLM-trace listing endpoint. Mirrors the
 * shape of other paged responses in the API ({@code DocumentListResponse},
 * {@code InboxListResponse}). {@code totalCount} drives the pagination
 * widget; {@code page} is zero-based.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("llmtrace")
public class LlmTraceListResponse {

    @Builder.Default
    private List<LlmTraceDto> items = new ArrayList<>();

    private int page;
    private int pageSize;
    private long totalCount;
}
