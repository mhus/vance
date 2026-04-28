package de.mhus.vance.api.insights;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Read-only view of a memory entry for the insights inspector.
 *
 * <p>{@link #kind} is one of {@code ARCHIVED_CHAT}, {@code PLAN},
 * {@code SCRATCHPAD}, {@code OTHER}. {@link #sourceRefs} are ids of
 * records this memory was derived from (chat-message ids for
 * compactions, etc.). {@link #supersededByMemoryId} closes the audit
 * chain when a newer compaction replaces this one.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("insights")
public class MemoryInsightsDto {

    private String id;

    private String kind;

    private @Nullable String title;

    private String content;

    @Builder.Default
    private List<String> sourceRefs = new ArrayList<>();

    @Builder.Default
    private Map<String, Object> metadata = new LinkedHashMap<>();

    private @Nullable String supersededByMemoryId;

    private @Nullable Instant supersededAt;

    private @Nullable Instant createdAt;
}
