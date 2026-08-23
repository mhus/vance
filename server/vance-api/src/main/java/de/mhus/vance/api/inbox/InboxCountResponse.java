package de.mhus.vance.api.inbox;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of {@code GET /brain/{tenant}/inbox/count} — how many inbox
 * items are still pending for the requested assignee(s).
 *
 * <p>Exists so the Web-UI topbar badge can answer "do I have new inbox
 * messages?" without pulling the whole item list (bodies and payloads
 * included) on every page load.
 *
 * <p><b>Two groups of numbers, and they answer different questions.</b>
 * {@code unread*} is the alarm — what wants attention now. {@code pending*} is
 * the stock — what is open at all. They are not the same population and
 * neither is a rename of the other.
 *
 * <ul>
 *   <li>{@code unread} — threads with something unopened for this user, not
 *       archived. <b>This is the number in the badge.</b> A decision that was
 *       read and deliberately held back does not appear here: a badge that
 *       cannot reach zero without deciding trains people to dismiss.</li>
 *   <li>{@code unreadRequiresAction} — of those, the ones that are an open ask
 *       assigned to this user. <b>This colours the badge</b>, and it comes from
 *       the same population as {@code unread} on purpose: colouring on
 *       {@code requiresAction} instead would paint it red because something is
 *       open somewhere, even when every unread thread is a harmless output.</li>
 *   <li>{@code pending} — all items in {@code PENDING}, including pure
 *       outputs (shares, notes) that need no answer. <b>Shown in the
 *       tooltip</b>, so "how much is actually lying here" is answerable without
 *       opening the inbox.</li>
 *   <li>{@code requiresAction} — the subset of {@code pending} a process waits
 *       on. Always {@code <= pending}.</li>
 * </ul>
 *
 * <p>See {@code planning/maximegalon.md} §4b.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("inbox")
public class InboxCountResponse {
    private long unread;
    private long unreadRequiresAction;
    private long pending;
    private long requiresAction;
}
