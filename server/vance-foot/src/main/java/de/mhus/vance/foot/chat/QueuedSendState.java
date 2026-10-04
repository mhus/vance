package de.mhus.vance.foot.chat;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Which queued sends are <em>ours</em> — the ids of messages this client
 * persisted while a turn was already in flight ("active message queue",
 * see {@code planning/active-message-queue.md} §4 P3).
 *
 * <p>Exists for one line of feedback: when the engine picks a message up
 * at its next loop boundary, the {@code process-queue} notification carries
 * only drained ids and covers <em>every</em> queue entry — worker events,
 * parent notifications, other users in a collab session. Announcing all of
 * them would be noise; announcing the ones this user is waiting on is the
 * closure between "⋯ queued" and "the answer is being worked on".
 *
 * <p>Session-lifetime state, deliberately not persisted: a foot that
 * restarted did not queue anything (its messages were accepted by the
 * earlier run and are visible in the chat history).
 */
@Component
public class QueuedSendState {

    private final Set<String> waitingOn = ConcurrentHashMap.newKeySet();

    /** Remember an acked {@code messageId} that sits behind a running turn. */
    public void track(String messageId) {
        if (messageId != null && !messageId.isBlank()) {
            waitingOn.add(messageId);
        }
    }

    /**
     * Drop the given drained ids from the watch list and report how many of
     * them were ours — {@code 0} means the drain is machinery traffic and
     * must stay quiet.
     */
    public int pickup(java.util.Collection<String> drainedIds) {
        int mine = 0;
        for (String id : drainedIds) {
            if (waitingOn.remove(id)) {
                mine++;
            }
        }
        return mine;
    }
}
