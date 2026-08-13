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
 * <ul>
 *   <li>{@code pending} — all items in {@code PENDING}, including pure
 *       outputs (shares, notes) that need no answer.</li>
 *   <li>{@code requiresAction} — the subset a process actually waits on.
 *       Always {@code <= pending}.</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("inbox")
public class InboxCountResponse {
    private long pending;
    private long requiresAction;
}
