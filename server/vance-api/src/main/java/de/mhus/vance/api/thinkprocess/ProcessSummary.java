package de.mhus.vance.api.thinkprocess;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Lightweight projection of a {@code ThinkProcessDocument} — used by
 * {@code process-list} and the {@code process_list} LLM tool. Plain
 * data, no engine state.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("thinkprocess")
public class ProcessSummary {

    /** {@code ThinkProcessDocument.id} (Mongo id). */
    private String id;

    private String name;

    private @Nullable String title;

    private String thinkEngine;

    private @Nullable String thinkEngineVersion;

    private @Nullable String goal;

    private ThinkProcessStatus status;

    /** Set only when {@link #status} is {@link ThinkProcessStatus#CLOSED}. */
    private @Nullable CloseReason closeReason;

    private @Nullable Instant createdAt;
}
