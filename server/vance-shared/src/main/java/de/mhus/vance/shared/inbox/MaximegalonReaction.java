package de.mhus.vance.shared.inbox;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One emoji reaction, on a thread or on a single message.
 *
 * <p>The array of user-ids carries both the attribution and the count — the
 * same shape as {@code readBy} and {@code unreadFor}, and the reason no
 * separate counter exists: a number that can drift from the list it counts is
 * a second truth.
 *
 * <p><b>{@link #key} is a shortcode, never a unicode character</b>
 * ({@code thumbsup}, not {@code 👍}). Skin-tone variants are distinct
 * codepoints, so a unicode key would file 👍 and 👍🏽 as two different
 * reactions with a count of one each. Concretely, with
 * {@code emoji-picker-element}: {@code detail.emoji.shortcodes[0]} first,
 * {@code detail.emoji.unicode} as the fallback — <b>never</b>
 * {@code detail.unicode}, which is the skin-tone-applied value.
 *
 * <p>A reaction is <b>not a decision</b> (a 👍 on an ask does not answer it —
 * that path carries {@code AnswerPayload}, the effect and the audit trail) and
 * it does <b>not</b> mark a thread unread: reactions are the deliberately
 * quiet channel. See {@code planning/maximegalon.md} §3c.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaximegalonReaction {

    /** Emoji shortcode, e.g. {@code thumbsup}, {@code eyes}. */
    private String key = "";

    /** Who reacted. Membership is the attribution and the count. */
    @Builder.Default
    private List<String> userIds = new ArrayList<>();
}
