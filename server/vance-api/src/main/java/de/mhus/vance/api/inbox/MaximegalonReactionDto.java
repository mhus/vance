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
 * One emoji reaction as the client sees it: the shortcode plus who reacted.
 *
 * <p>The user list travels rather than a count, because the client needs both —
 * the number on the chip and whether the current user is in it, to render the
 * chip as pressed and to toggle it. Sending a count plus a boolean would be two
 * derived values where one list does.
 *
 * <p>{@code key} is a shortcode ({@code thumbsup}), never a unicode character:
 * skin-tone variants are separate codepoints, so 👍 and 👍🏽 would otherwise be
 * two reactions of one each.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("inbox")
public class MaximegalonReactionDto {

    private String key;

    @Builder.Default
    private List<String> userIds = new ArrayList<>();
}
