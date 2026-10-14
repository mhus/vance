package de.mhus.vance.brain.trillian;

import de.mhus.vance.brain.trillian.nature.TrillianNature;
import de.mhus.vance.brain.trillian.nature.TrillianNatureRegistry;
import de.mhus.vance.shared.inbox.MaximegalonCreatedEvent;
import de.mhus.vance.shared.inbox.MaximegalonDocument;
import de.mhus.vance.shared.inbox.MaximegalonMessage;
import de.mhus.vance.shared.inbox.MaximegalonMessageEvent;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The Trillian side of a thread contribution: wakes the conversation head of
 * a multi-headed Trillian when someone writes to one of its threads.
 *
 * <p><b>Both creation shapes.</b> A conversation reaches the inbox either as
 * an embedded message on {@link MaximegalonCreatedEvent} (created with the
 * opener in one write) or as a later {@link MaximegalonMessageEvent} — the
 * listener treats both as "a contribution landed" and ignores everything
 * else (answers, read marks, effects). Where the exposure entry (Phase 2)
 * settles is its own choice; the head does not care.
 *
 * <p><b>Conditions, all of them cheap-first.</b> Not a conversation (asks
 * carry rights and never reach a head, maximegalon §8), not assigned to a
 * Trillian account, the Trillian's loop not alive, the Nature not
 * multi-headed, the author the Trillian itself — every one of these drops
 * the event before anything is spawned. The multi-head check is the
 * structural one: single-headed Natures answer threads on their own loop
 * ({@code inbox_unread}), and a thread reaching both would be answered
 * twice.
 *
 * <p><b>No exposure check yet.</b> Phase 1 wires the mechanics; the gate
 * against the exposure configuration (who may open conversations at all)
 * sits at the protocol entry and arrives with Phase 2 —
 * {@code planning/trillian-exposure.md} §3.5/§5.
 *
 * <p>Cheap by contract, same as {@link TrillianSessionReplyListener}: this
 * runs synchronously on the appending thread, and the common case (a thread
 * between humans) costs nothing at all.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TrillianThreadListener {

    private final TrillianOwnSessions ownSessions;
    private final TrillianNatureRegistry natureRegistry;
    private final TrillianHeadService headService;
    private final TrillianWakeupService wakeupService;

    @EventListener
    public void onThreadCreated(MaximegalonCreatedEvent event) {
        MaximegalonDocument item = event.item();
        List<MaximegalonMessage> messages = item.getMessages() == null ? List.of() : item.getMessages();
        if (!messages.isEmpty()) {
            dispatch(item, messages.get(messages.size() - 1));
            return;
        }
        // The other creation shape every discussion opener produces (the
        // inbox REST endpoint, the exposure door): no messages, the body IS
        // the contribution. Whether the thread is a conversation at all —
        // a discussion, not a notice that merely carries a body — is
        // dispatch's question, as for every contribution.
        TrillianHeadService.openerOf(item).ifPresent(opener -> dispatch(item, opener));
    }

    @EventListener
    public void onThreadMessage(MaximegalonMessageEvent event) {
        dispatch(event.item(), event.message());
    }

    private void dispatch(MaximegalonDocument item, MaximegalonMessage message) {
        try {
            if (!TrillianHeadService.isConversation(item)) {
                return;
            }
            String account = item.getAssignedToUserId();
            if (account == null || !account.startsWith(TrillianSessionBootstrapper.ACCOUNT_PREFIX)) {
                return;
            }
            if (account.equals(message.getAuthorUserId())) {
                return; // the Trillian's own answer — it must not wake itself
            }
            ThinkProcessDocument loop =
                    ownSessions.liveLoopOf(item.getTenantId(), account).orElse(null);
            if (loop == null) {
                log.trace(
                        "Trillian head: no live loop for '{}' — contribution to thread '{}' rests",
                        account,
                        item.getId());
                return;
            }
            TrillianNature nature = natureRegistry.resolve(TrillianSessionBootstrapper.readNature(loop));
            if (!nature.multiHead()) {
                // Single-headed: the loop answers its own threads through its
                // self-check (inbox_unread). Make that due now instead of
                // leaving the counterpart to the ladder's 10-60 minutes.
                wakeupService.wakeNow(loop.getId());
                return;
            }
            headService.dispatchContribution(loop, nature, item, message);
        } catch (RuntimeException e) {
            log.warn("Trillian head: contribution to thread '{}' failed: {}", item.getId(), e.toString());
        }
    }
}
