package de.mhus.vance.api.thinkprocess;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import de.mhus.vance.api.chat.ChatMessageDto;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Reply to {@code process-messages} — one process's conversation plus the
 * bit of live state a detail view needs in its header.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("thinkprocess")
public class ProcessMessagesResponse {

    private String processId = "";

    private String name = "";

    private @Nullable String thinkEngine;

    private @Nullable ThinkProcessStatus status;

    private @Nullable CloseReason closeReason;

    /** Oldest first, interim notes included (the UI dims them). */
    @Builder.Default
    private List<ChatMessageDto> messages = new ArrayList<>();

    /** How many older messages the {@code limit} cut away, or {@code null}. */
    private @Nullable Integer olderTruncated;
}
