package de.mhus.vance.api.inbox;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /brain/{tenant}/inbox/{id}/react}.
 *
 * <p>{@code messageId} names the message to react to, or is {@code null} for
 * the thread's own title and body — the thread is the root node of its tree, so
 * it carries reactions like any node.
 *
 * <p>{@code key} is an emoji <b>shortcode</b> ({@code thumbsup}), not the
 * character: skin-tone variants are separate codepoints and would file the same
 * reaction twice. With {@code emoji-picker-element} that is
 * {@code detail.emoji.shortcodes[0]}, falling back to
 * {@code detail.emoji.unicode} — never {@code detail.unicode}, which carries
 * the applied skin tone.
 *
 * <p>A reaction never marks a thread unread: this is the quiet channel.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("inbox")
public class InboxReactRequest {

    private @Nullable String messageId;

    @NotBlank
    private String key;

    /** {@code true} adds the reaction, {@code false} takes it back. */
    private boolean on;
}
