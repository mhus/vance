package de.mhus.vance.shared.inbox;

import de.mhus.vance.api.inbox.MaximegalonStatus;
import de.mhus.vance.shared.user.maintenance.UserDataHandler;
import de.mhus.vance.shared.user.maintenance.UserTombstone;
import java.time.Instant;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Everything a deleted account leaves in the inbox — and it is one collection
 * with fields of all three classes at once, which is why this handler is
 * written by hand.
 *
 * <ol>
 *   <li><b>Participation is authority</b> ({@code participants},
 *       {@code readBy}, {@code unreadFor}, and the same two lists inside every
 *       message). Stripped, not tombstoned. {@code unreadFor} is the index
 *       behind the badge query, so a ghost entry is counted noise; and
 *       participation is the right to contribute, which a gone account does not
 *       keep.</li>
 *   <li><b>An open decision assigned to them cannot be made by anyone.</b>
 *       The thread is archived with a reason naming the account, because
 *       leaving it {@code PENDING} produces an item that never reaches zero —
 *       against the rule that the badge must be clearable. Reassigning was the
 *       alternative and needs an authority this layer cannot ask for: the
 *       abstract permission SPI cannot enumerate who the admins are.</li>
 *   <li><b>Authorship is a record</b> ({@code originatorUserId},
 *       {@code assignedToUserId} on the now-archived thread, and every
 *       message's {@code authorUserId}). Tombstoned, so the thread still says
 *       who raised it and who was asked.</li>
 * </ol>
 *
 * <p>Threads themselves are never deleted. A thread belongs to the people in it
 * and outlives what it was about — the same reasoning that keeps it out of the
 * project delete.
 *
 * <p>On <b>rename</b> all of it moves, participation included: it is the same
 * person, so they keep their place in the conversation.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MaximegalonUserDataHandler implements UserDataHandler {

    /** After the sessions are gone; threads are not session-scoped. */
    public static final int ORDER = 1100;

    private static final String F_ASSIGNEE = "assignedToUserId";
    private static final String F_ORIGINATOR = "originatorUserId";
    private static final String F_PARTICIPANTS = "participants";
    private static final String F_READ_BY = "readBy";
    private static final String F_UNREAD_FOR = "unreadFor";

    private final MongoTemplate mongoTemplate;

    @Override
    public String id() {
        return "inbox-threads";
    }

    @Override
    public Set<String> collections() {
        return Set.of(MaximegalonDocument.COLLECTION);
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public long count(String tenantId, String userName) {
        return mongoTemplate.count(touching(tenantId, userName), MaximegalonDocument.class);
    }

    @Override
    public long delete(String tenantId, String userName) {
        long archived = archiveOpenDecisions(tenantId, userName);
        long stripped = stripParticipation(tenantId, userName);
        long tombstoned = tombstoneAuthorship(tenantId, userName, UserTombstone.of(userName));
        if (archived > 0) {
            log.info("Inbox: archived {} open thread(s) assigned to the deleted account '{}'",
                    archived, userName);
        }
        // One number for the operator: how many threads this touched at all.
        return Math.max(stripped, tombstoned);
    }

    @Override
    public @Nullable String deleteNote(String tenantId, String userName) {
        long open = mongoTemplate.count(openDecisions(tenantId, userName),
                MaximegalonDocument.class);
        if (open == 0) {
            return null;
        }
        return open + " open decision(s) were assigned to this account — archived, nobody"
                + " else could have made them";
    }

    @Override
    public long rename(String tenantId, String userName, String newUserName) {
        // Everything, including participation: same person, same place in the
        // conversation. No archiving — an open decision is still theirs.
        long moved = mongoTemplate.updateMulti(
                        new Query(Criteria.where("tenantId").is(tenantId)
                                .and(F_ASSIGNEE).is(userName)),
                        new Update().set(F_ASSIGNEE, newUserName),
                        MaximegalonDocument.class)
                .getModifiedCount();
        moved += renameInLists(tenantId, userName, newUserName);
        moved += tombstoneAuthorship(tenantId, userName, newUserName);
        return moved;
    }

    /**
     * Archives the threads whose open decision was this account's to make.
     *
     * <p>Before the assignee is tombstoned, so the reason can still name the
     * account — and so a re-run finds nothing left to archive.
     */
    private long archiveOpenDecisions(String tenantId, String userName) {
        Update update = new Update()
                .set("status", MaximegalonStatus.ARCHIVED)
                .set("resolvedAt", Instant.now())
                .set("resolverReason",
                        "assignee '" + userName + "' was deleted — nobody could decide this");
        return mongoTemplate.updateMulti(openDecisions(tenantId, userName), update,
                MaximegalonDocument.class).getModifiedCount();
    }

    /**
     * Removes the account from every participation and read-state list.
     *
     * <p>Two passes, deliberately different. The thread-level lists are a
     * {@code $pull} — one update over all matches. The per-message
     * {@code readBy} lists are edited in Java: reaching into an array inside an
     * array wants either {@code $[]} or {@code arrayFilters}, and the exact
     * support for {@code $pull} through those is not something to be almost
     * sure about in a delete path. The affected threads are few and bounded
     * ({@code MAX_MESSAGES}), so reading them is cheap and certainly correct.
     *
     * <p>The per-message lists are not cosmetic: {@code unreadFor} is
     * documented as rebuildable from them, so a ghost left there would come
     * back into the badge index — for a name a future account can hold.
     */
    private long stripParticipation(String tenantId, String userName) {
        long changed = mongoTemplate.updateMulti(
                        new Query(Criteria.where("tenantId").is(tenantId)
                                .orOperator(
                                        Criteria.where(F_PARTICIPANTS).is(userName),
                                        Criteria.where(F_READ_BY).is(userName),
                                        Criteria.where(F_UNREAD_FOR).is(userName))),
                        new Update()
                                .pull(F_PARTICIPANTS, userName)
                                .pull(F_READ_BY, userName)
                                .pull(F_UNREAD_FOR, userName),
                        MaximegalonDocument.class)
                .getModifiedCount();
        return changed + editMessages(tenantId, userName,
                (message, target) -> message.getReadBy().remove(userName));
    }

    /** Carries participation to a new name — the rename counterpart. */
    private long renameInLists(String tenantId, String userName, String newUserName) {
        Query query = new Query(Criteria.where("tenantId").is(tenantId)
                .orOperator(
                        Criteria.where(F_PARTICIPANTS).is(userName),
                        Criteria.where(F_READ_BY).is(userName),
                        Criteria.where(F_UNREAD_FOR).is(userName)));
        // Add before pull, and in two updates because Mongo refuses $pull and
        // $addToSet on one field in one go. This order matters: a crash between
        // them leaves the person listed twice under two names, which is visible
        // and harmless. The other order drops them out of the conversation.
        mongoTemplate.updateMulti(query,
                new Update()
                        .addToSet(F_PARTICIPANTS, newUserName)
                        .addToSet(F_READ_BY, newUserName)
                        .addToSet(F_UNREAD_FOR, newUserName),
                MaximegalonDocument.class);
        long changed = mongoTemplate.updateMulti(query,
                new Update()
                        .pull(F_PARTICIPANTS, userName)
                        .pull(F_READ_BY, userName)
                        .pull(F_UNREAD_FOR, userName),
                MaximegalonDocument.class).getModifiedCount();
        return changed + editMessages(tenantId, userName, (message, target) -> {
            if (message.getReadBy().remove(userName)) {
                message.getReadBy().add(newUserName);
            }
        });
    }

    /** Rewrites authorship — originator, assignee and every message author. */
    private long tombstoneAuthorship(String tenantId, String userName, String target) {
        long changed = mongoTemplate.updateMulti(
                        new Query(Criteria.where("tenantId").is(tenantId)
                                .and(F_ORIGINATOR).is(userName)),
                        new Update().set(F_ORIGINATOR, target),
                        MaximegalonDocument.class)
                .getModifiedCount();
        changed += mongoTemplate.updateMulti(
                        new Query(Criteria.where("tenantId").is(tenantId)
                                .and(F_ASSIGNEE).is(userName)),
                        new Update().set(F_ASSIGNEE, target),
                        MaximegalonDocument.class)
                .getModifiedCount();
        return changed + editMessages(tenantId, userName, (message, unused) -> {
            if (userName.equals(message.getAuthorUserId())) {
                message.setAuthorUserId(target);
            }
        });
    }

    /**
     * Applies {@code edit} to every message of every thread that names the
     * account inside its message array, and saves the threads that changed.
     *
     * <p>The one place this handler leaves the query layer. Note the
     * {@code $or}: a thread qualifies through either the author or the
     * per-message read state, and loading only one of the two would silently
     * skip the other.
     *
     * @return how many threads were written
     */
    private long editMessages(String tenantId, String userName, MessageEdit edit) {
        Query query = new Query(Criteria.where("tenantId").is(tenantId)
                .orOperator(
                        Criteria.where("messages.authorUserId").is(userName),
                        Criteria.where("messages.readBy").is(userName)));
        long written = 0;
        for (MaximegalonDocument thread
                : mongoTemplate.find(query, MaximegalonDocument.class)) {
            String before = thread.getMessages().toString();
            for (MaximegalonMessage message : thread.getMessages()) {
                edit.apply(message, userName);
            }
            if (!before.equals(thread.getMessages().toString())) {
                mongoTemplate.save(thread);
                written++;
            }
        }
        return written;
    }

    @FunctionalInterface
    private interface MessageEdit {
        void apply(MaximegalonMessage message, String userName);
    }

    /** Threads this account appears in at all. */
    private Query touching(String tenantId, String userName) {
        return new Query(Criteria.where("tenantId").is(tenantId)
                .orOperator(
                        Criteria.where(F_ASSIGNEE).is(userName),
                        Criteria.where(F_ORIGINATOR).is(userName),
                        Criteria.where(F_PARTICIPANTS).is(userName),
                        Criteria.where(F_READ_BY).is(userName),
                        Criteria.where(F_UNREAD_FOR).is(userName),
                        Criteria.where("messages.authorUserId").is(userName)));
    }

    /** Threads with an unanswered decision assigned to this account. */
    private Query openDecisions(String tenantId, String userName) {
        return new Query(Criteria.where("tenantId").is(tenantId)
                .and(F_ASSIGNEE).is(userName)
                .and("status").is(MaximegalonStatus.PENDING));
    }
}
