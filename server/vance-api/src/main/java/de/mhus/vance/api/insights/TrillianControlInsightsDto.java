package de.mhus.vance.api.insights;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import de.mhus.vance.api.session.SessionStatus;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * The control side of a Trillian pair: the chat session a human opens
 * and the chat-process driving it. Read-only summary for the insights
 * inspector — see {@link TrillianInsightsDto}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("insights")
public class TrillianControlInsightsDto {

    /** Business id of the control session ({@code sess_...}). */
    private String sessionId;

    private @Nullable SessionStatus status;

    private String projectId;

    /** Mongo id of the control chat-process — the addressing key for pause/resume. */
    private String processId;

    private String processName;

    private ThinkProcessStatus processStatus;

    /** Nature letter the pair runs on ({@code void}, {@code adam}, …). */
    private @Nullable String nature;

    private @Nullable Instant createdAt;
}
