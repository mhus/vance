package de.mhus.vance.api.thinkprocess;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Reply to {@code process-compact}. {@code compacted=false} means the
 * call was a no-op (history too short, summarizer failed, etc.) — the
 * {@code reason} is meant to be human-readable so the client can
 * surface it without translation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("thinkprocess")
public class ProcessCompactResponse {

    private String processName;

    private boolean compacted;

    private int messagesCompacted;

    private int summaryChars;

    /** {@code MemoryDocument.id} of the new compaction record, if any. */
    private @Nullable String memoryId;

    /** Previous active compaction memory that was superseded, if any. */
    private @Nullable String supersededMemoryId;

    /** Human-readable note (no-op reason or success summary). */
    private @Nullable String reason;
}
