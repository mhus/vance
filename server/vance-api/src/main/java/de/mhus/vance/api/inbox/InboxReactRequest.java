package de.mhus.vance.api.inbox;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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

    @Size(max = 64)
    private @Nullable String messageId;

    /**
     * The shortcode — {@code thumbsup}, not 👍. See {@link #MAX_KEY_CHARS}.
     *
     * <p>Bounded but not shaped. A grammar of {@code [a-z0-9_+-]} would be the
     * obvious rule and would break the documented fallback for emoji the picker
     * has no shortcode for ({@code detail.emoji.unicode}, which is a character).
     * So the length is enforced here and the real defence sits where the actual
     * risk is: {@code MaximegalonService.MAX_REACTION_KEYS} caps how many
     * <em>distinct</em> keys one node may carry, because it is the number of
     * array entries, not the size of one, that walks a thread document towards
     * the 16 MB limit.
     */
    @NotBlank
    @Size(max = MAX_KEY_CHARS)
    private String key;

    /**
     * Upper bound on one key. Long enough for the longest real shortcode
     * ({@code woman_technologist_medium_dark_skin_tone} is 41) and for a
     * multi-codepoint emoji sequence, short enough that it cannot be a payload.
     */
    public static final int MAX_KEY_CHARS = 64;

    /** {@code true} adds the reaction, {@code false} takes it back. */
    private boolean on;
}
