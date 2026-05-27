package de.mhus.vance.api.insights;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import de.mhus.vance.api.chat.ChatRole;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Read-only view of one chat message for the insights inspector.
 *
 * <p>{@link #archivedInMemoryId} flags messages that have been
 * compacted into a memory entry — the UI shows them dimmed and links
 * to the memory.
 *
 * <p>{@link #tags} mirrors {@code ChatMessageDocument.tags}: ordered
 * marker values written by the tool-dispatcher hook and by Prak's
 * {@code SpanStrengthDeriver}. Examples: {@code STRENGTH:strong},
 * {@code FILE_EDIT}, {@code TOOL_CALL:read_file},
 * {@code RESOURCE:CLIENT_FILE:/abs/Foo.java}. The UI surfaces the
 * {@code STRENGTH:*} entry as a prominent badge so operators can see
 * Prak's ranking at a glance.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("insights")
public class ChatMessageInsightsDto {

    private String id;

    private ChatRole role;

    private String content;

    private @Nullable String archivedInMemoryId;

    @Builder.Default
    private List<String> tags = new ArrayList<>();

    private @Nullable Instant createdAt;
}
