package de.mhus.vance.api.inbox;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of {@code GET /brain/{tenant}/inbox/tags}. Lists every
 * tag currently used across the requested userIds' inbox items
 * (alphabetically sorted, deduped). Drives the tag-filter sidebar
 * in the Web-UI inbox editor.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("inbox")
public class InboxTagsResponse {
    @Builder.Default
    private List<String> tags = new ArrayList<>();
}
