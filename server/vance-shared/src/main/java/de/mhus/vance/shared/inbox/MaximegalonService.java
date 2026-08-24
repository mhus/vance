package de.mhus.vance.shared.inbox;

import com.mongodb.client.result.UpdateResult;
import org.bson.types.ObjectId;
import de.mhus.vance.api.inbox.AnswerOutcome;
import de.mhus.vance.api.inbox.AnswerPayload;
import de.mhus.vance.api.inbox.Criticality;
import de.mhus.vance.api.inbox.MaximegalonStatus;
import de.mhus.vance.api.inbox.MaximegalonType;
import de.mhus.vance.api.inbox.ResolvedBy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Sort;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.ArrayOperators;
import org.springframework.data.mongodb.core.aggregation.ConditionalOperators;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Lifecycle and lookup for {@link MaximegalonDocument}. The single
 * entry point to inbox data — analogous to
 * {@code ThinkProcessService} for processes.
 *
 * <h2>Auto-default for LOW criticality</h2>
 * Items with {@link Criticality#LOW} <em>and</em> a {@code default}
 * key in their payload are auto-answered at creation time:
 * {@link MaximegalonStatus#ANSWERED}, {@link ResolvedBy#AUTO_DEFAULT},
 * answer carries the default. The originating process gets the
 * answer immediately via the steer-message path; no user is
 * bothered. The item is still persisted with the audit marker.
 *
 * <h2>Events</h2>
 * Publishes Spring application events on key transitions —
 * {@link MaximegalonCreatedEvent}, {@link MaximegalonAnsweredEvent},
 * {@link MaximegalonDelegatedEvent}, {@link MaximegalonArchivedEvent}.
 * The notification dispatcher subscribes to {@code Created} and
 * {@code Delegated} (new assignee gets pinged); the steer-router
 * subscribes to {@code Answered}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MaximegalonService {

    private static final String F_ID = "_id";
    private static final String F_TENANT = "tenantId";
    private static final String F_STATUS = "status";
    private static final String F_ASSIGNED = "assignedToUserId";
    private static final String F_REQUIRES_ACTION = "requiresAction";
    private static final String F_MESSAGES = "messages";
    private static final String F_PARTICIPANTS = "participants";
    private static final String F_READ_BY = "readBy";
    private static final String F_UNREAD_FOR = "unreadFor";
    private static final String F_REACTIONS = "reactions";
    private static final String F_DOC_REF_ID = "documentRef.documentId";

    /**
     * Upper bound on a thread's embedded discussion.
     *
     * <p>Not a guess at what people need but a property of the storage
     * decision: the messages live inside the thread document, and an unbounded
     * array walks into Mongo's 16 MB limit — a document that has burst it can
     * be neither read nor repaired through the normal API. For a single matter
     * heading for a single decision the bound is also simply healthy; it says
     * the same thing as "a thread ends".
     */
    public static final int MAX_MESSAGES = 500;

    /**
     * Distinct reaction keys one node (the thread body, or one message) may
     * carry.
     *
     * <p>The bound that matters for reactions. A key's <em>length</em> is
     * capped at the wire ({@code InboxReactRequest.MAX_KEY_CHARS}), but every
     * distinct key adds an array entry, and the array lives inside the same
     * document as the discussion — so without this a client could grow a thread
     * towards the 16 MB limit one novel key at a time, without ever sending
     * anything oversized.
     *
     * <p>Adding past the cap is refused, not silently dropped: the client asked
     * for something and has to learn it did not happen. Taking a reaction back
     * always works — a rule that could not be undone would be a trap.
     */
    public static final int MAX_REACTION_KEYS = 32;

    /** Conventional payload key for the LOW-auto-default value. */
    public static final String PAYLOAD_DEFAULT_KEY = "default";

    private final MaximegalonRepository repository;
    private final MongoTemplate mongoTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private final InboxEffectRegistry effectRegistry;

    // ────────────────── Create ──────────────────

    /**
     * Persists a new item. If {@link Criticality#LOW} and the
     * payload contains a {@code default}, the item is created
     * already in {@code ANSWERED} state with
     * {@link ResolvedBy#AUTO_DEFAULT}.
     *
     * @return the saved item (with id assigned)
     */
    public MaximegalonDocument create(MaximegalonDocument toCreate) {
        Instant now = Instant.now();
        boolean autoAnswered = shouldAutoAnswer(toCreate);

        // Always start with a CREATED history entry.
        List<MaximegalonHistoryEntry> history = toCreate.getHistory();
        if (history == null) history = new ArrayList<>();
        history.add(MaximegalonHistoryEntry.builder()
                .action("CREATED")
                .actor(toCreate.getOriginatorUserId())
                .at(now)
                .build());

        if (autoAnswered) {
            Object defaultValue = toCreate.getPayload().get(PAYLOAD_DEFAULT_KEY);
            Map<String, Object> answerValue = wrapDefaultAsAnswerValue(
                    toCreate.getType(), defaultValue);
            toCreate.setStatus(MaximegalonStatus.ANSWERED);
            toCreate.setAnswer(AnswerPayload.builder()
                    .outcome(AnswerOutcome.DECIDED)
                    .value(answerValue)
                    .answeredBy("system:auto-default")
                    .build());
            toCreate.setResolvedBy(ResolvedBy.AUTO_DEFAULT);
            toCreate.setResolvedAt(now);
            history.add(MaximegalonHistoryEntry.builder()
                    .action("ANSWERED")
                    .actor("system:auto-default")
                    .details("LOW criticality auto-default")
                    .at(now)
                    .build());
        }
        toCreate.setHistory(history);
        seedThreadState(toCreate, autoAnswered);

        MaximegalonDocument saved = repository.save(toCreate);
        log.info("Created inbox item id='{}' tenant='{}' assignee='{}' type={} crit={} requiresAction={} status={}",
                saved.getId(), saved.getTenantId(), saved.getAssignedToUserId(),
                saved.getType(), saved.getCriticality(),
                saved.isRequiresAction(), saved.getStatus());

        eventPublisher.publishEvent(new MaximegalonCreatedEvent(saved));
        if (autoAnswered) {
            eventPublisher.publishEvent(new MaximegalonAnsweredEvent(saved));
        }
        return saved;
    }

    /**
     * Fills participants and read state on a thread being created, unless the
     * caller already supplied participants of its own.
     *
     * <p>The creator counts as having read what they wrote, so they start in
     * {@code readBy} and out of {@code unreadFor} — otherwise every posting
     * tool would light up its own author's badge.
     *
     * <p><b>An auto-answered thread starts fully read.</b> LOW criticality with
     * a default is decided at creation time and deliberately bothers nobody;
     * leaving it unread would put a badge on something no human ever needs to
     * look at, which is exactly what the auto-default exists to avoid.
     */
    private static void seedThreadState(MaximegalonDocument doc, boolean autoAnswered) {
        if (doc.getParticipants() == null || doc.getParticipants().isEmpty()) {
            List<String> participants = new ArrayList<>();
            addIfPresent(participants, doc.getOriginatorUserId());
            addIfPresent(participants, doc.getAssignedToUserId());
            doc.setParticipants(participants);
        }
        String creator = doc.getOriginatorUserId();
        List<String> readBy = new ArrayList<>();
        addIfPresent(readBy, creator);
        doc.setReadBy(readBy);

        List<String> unread = new ArrayList<>();
        if (!autoAnswered) {
            for (String p : doc.getParticipants()) {
                if (!p.equals(creator)) unread.add(p);
            }
        }
        doc.setUnreadFor(unread);
    }

    /** Appends a non-blank value that isn't already in the list. */
    private static void addIfPresent(List<String> target, @Nullable String value) {
        if (value != null && !value.isBlank() && !target.contains(value)) {
            target.add(value);
        }
    }

    /**
     * Decision rule: auto-answer iff item is asking ({@code requiresAction}),
     * criticality is LOW, and payload carries a {@code default}.
     */
    private static boolean shouldAutoAnswer(MaximegalonDocument doc) {
        if (!doc.isRequiresAction()) return false;
        if (doc.getCriticality() != Criticality.LOW) return false;
        if (doc.getPayload() == null) return false;
        return doc.getPayload().containsKey(PAYLOAD_DEFAULT_KEY);
    }

    /** Maps the bare {@code default} value to the type-shaped answer-value map. */
    private static Map<String, Object> wrapDefaultAsAnswerValue(
            MaximegalonType type, Object defaultValue) {
        Map<String, Object> v = new LinkedHashMap<>();
        switch (type) {
            case APPROVAL -> v.put("approved",
                    defaultValue instanceof Boolean b ? b
                            : "yes".equalsIgnoreCase(String.valueOf(defaultValue)));
            case DECISION -> v.put("chosen", defaultValue);
            case FEEDBACK -> v.put("text", String.valueOf(defaultValue));
            case ORDERING, STRUCTURE_EDIT -> v.put("value", defaultValue);
            default -> { /* outputs don't auto-answer */ }
        }
        return v;
    }

    // ────────────────── Read ──────────────────

    public Optional<MaximegalonDocument> findById(String tenantId, String id) {
        return repository.findByIdAndTenantId(id, tenantId);
    }

    public List<MaximegalonDocument> listForUser(
            String tenantId, String userId, @Nullable MaximegalonStatus status) {
        if (status == null) {
            return repository.findByTenantIdAndAssignedToUserId(tenantId, userId);
        }
        return repository.findByTenantIdAndAssignedToUserIdAndStatus(tenantId, userId, status);
    }

    /**
     * Generalised list with optional filters — used by the Web-UI's
     * three-pane inbox to drive both the "personal inbox" view (one
     * userId), the "team inbox" view (multiple member userIds, the
     * current user excluded by the caller) and the tag-filter that
     * sits on top.
     *
     * @param tenantId   tenant scope (mandatory)
     * @param userIds    list of {@code assignedToUserId} values to
     *                   include — empty means "no filter on user".
     *                   Typical: {@code [currentUser]} for personal,
     *                   {@code [memberA, memberB, …]} (excluding
     *                   self) for team-view.
     * @param status     filter on item status, or {@code null} =
     *                   any status.
     * @param tag        filter on a single tag, or {@code null} =
     *                   any tag.
     * @return matching items, sorted by {@code createdAt} desc so
     *         the freshest land at the top. <b>Without their messages</b> —
     *         see below.
     *
     * <p><b>The discussion is projected out.</b> A listing needs titles, not
     * transcripts, and the messages are embedded in the same document; without
     * this every inbox listing would drag every thread's full history along.
     * Consequence to be aware of: the returned documents are
     * <em>incomplete</em> and must never be handed to {@code save()} — that
     * would erase the messages. Every mutation in this service updates by
     * field for exactly that reason.
     */
    public List<MaximegalonDocument> listFiltered(
            String tenantId,
            List<String> userIds,
            @Nullable MaximegalonStatus status,
            @Nullable String tag) {
        Query query = Query.query(filterCriteria(tenantId, userIds, status, tag))
                .with(Sort.by(Sort.Direction.DESC, "createdAt"));
        query.fields().exclude(F_MESSAGES);
        return mongoTemplate.find(query, MaximegalonDocument.class);
    }

    /**
     * Threads whose object is the given document, newest first.
     *
     * <p><b>Unfiltered by visibility on purpose</b> — the caller filters. This
     * is the point where the inbox stops being a filing place (queried by
     * assignee, where the index already carries the answer) and becomes a query
     * about an <em>object</em>, which cuts across assignees. The service returns
     * what exists; who may see it is a decision with a name
     * ({@code InboxAuthz.maySee}) and belongs at the call site, next to the
     * subject, rather than buried in a lookup.
     *
     * <p>Messages are projected out, as in {@link #listFiltered}: a discussion
     * list wants titles.
     */
    public List<MaximegalonDocument> listByDocument(String tenantId, String documentId) {
        Query query = Query.query(Criteria.where(F_TENANT).is(tenantId)
                        .and(F_DOC_REF_ID).is(documentId))
                .with(Sort.by(Sort.Direction.DESC, "createdAt"));
        query.fields().exclude(F_MESSAGES);
        return mongoTemplate.find(query, MaximegalonDocument.class);
    }

    /**
     * How many contributions each of the given threads has, keyed by thread
     * id. Ids without a match are absent from the result.
     *
     * <p>The companion to {@link #listFiltered}, which projects the messages
     * out: a listing wants to say "3 replies" and must not transfer three
     * transcripts to do it. Mongo computes the {@code $size}, so this stays a
     * counting query no matter how long the discussions are.
     *
     * <p>Counted rather than read off a stored counter on purpose — the array
     * already carries its own length, and a counter beside it would be a
     * second truth that every append path has to remember to keep in step.
     */
    public Map<String, Integer> countMessages(String tenantId, Collection<String> itemIds) {
        if (itemIds.isEmpty()) return Map.of();
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(Criteria.where(F_ID).in(itemIds).and(F_TENANT).is(tenantId)),
                Aggregation.project().and(ArrayOperators.Size.lengthOfArray(
                                ConditionalOperators.ifNull(F_MESSAGES).then(List.of())))
                        .as(MessageCountRow.F_COUNT));
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (MessageCountRow row : mongoTemplate
                .aggregate(aggregation, MaximegalonDocument.class, MessageCountRow.class)
                .getMappedResults()) {
            if (row.getId() != null) counts.put(row.getId(), row.getCount());
        }
        return counts;
    }

    /** Read projection for {@link #countMessages} — one thread id, one number. */
    @Data
    static class MessageCountRow {
        static final String F_COUNT = "count";

        @Id
        private @Nullable String id;
        private int count;
    }

    /**
     * Counts the pending items of {@code userIds}, split by whether they
     * demand an answer. Drives the topbar inbox badge, which needs the two
     * numbers and never a body — so this counts in Mongo instead of
     * transferring every pending document just to read {@code .length}.
     *
     * <p>Both numbers are on {@code PENDING} only: an answered or archived
     * item is nothing the user has to look at. {@code requiresAction} is the
     * subset the originating process actually waits on; the rest are pure
     * outputs (shares, notes) that are worth showing but not worth colouring.
     *
     * @param userIds assignees to count over — empty means the whole tenant
     *                (not exposed in v1).
     */
    public PendingCounts countPending(String tenantId, List<String> userIds) {
        long total = mongoTemplate.count(
                Query.query(filterCriteria(tenantId, userIds, MaximegalonStatus.PENDING, null)),
                MaximegalonDocument.class);
        long requiresAction = mongoTemplate.count(
                Query.query(filterCriteria(tenantId, userIds, MaximegalonStatus.PENDING, null)
                        .and(F_REQUIRES_ACTION).is(true)),
                MaximegalonDocument.class);
        return new PendingCounts(total, requiresAction);
    }

    /** Single-item query by id within a tenant — used by every mutation. */
    private static Query byId(String tenantId, String itemId) {
        return Query.query(Criteria.where(F_ID).is(itemId).and(F_TENANT).is(tenantId));
    }

    /**
     * Shared criteria for the filtered read paths. Returns a fresh instance
     * per call — {@code Criteria.and(…)} mutates the receiver, so a shared
     * one would leak the caller's extra clauses into the next query.
     */
    private static Criteria filterCriteria(
            String tenantId,
            @Nullable List<String> userIds,
            @Nullable MaximegalonStatus status,
            @Nullable String tag) {
        Criteria criteria = Criteria.where(F_TENANT).is(tenantId);
        if (userIds != null && !userIds.isEmpty()) {
            criteria = criteria.and(F_ASSIGNED).in(userIds);
        }
        if (status != null) {
            criteria = criteria.and(F_STATUS).is(status);
        }
        if (tag != null && !tag.isBlank()) {
            criteria = criteria.and("tags").is(tag);
        }
        return criteria;
    }

    /**
     * Lists the unique tags currently used across {@code userIds}'
     * inbox items in a tenant. Drives the tag-filter sidebar; uses
     * a {@code distinct} projection so the full documents don't
     * load. Empty {@code userIds} → distinct across the whole
     * tenant (admin view, not exposed in v1 UI).
     */
    public List<String> distinctTags(String tenantId, List<String> userIds) {
        Criteria criteria = Criteria.where(F_TENANT).is(tenantId);
        if (userIds != null && !userIds.isEmpty()) {
            criteria = criteria.and(F_ASSIGNED).in(userIds);
        }
        return mongoTemplate.findDistinct(
                Query.query(criteria), "tags", MaximegalonDocument.class, String.class);
    }

    public List<MaximegalonDocument> listPendingForUser(String tenantId, String userId) {
        return listForUser(tenantId, userId, MaximegalonStatus.PENDING);
    }

    public PendingSummary summarizePendingForUser(String tenantId, String userId) {
        List<MaximegalonDocument> pending = listPendingForUser(tenantId, userId);
        Map<Criticality, Integer> byCrit = new LinkedHashMap<>();
        for (Criticality c : Criticality.values()) byCrit.put(c, 0);
        Instant oldest = null;
        for (MaximegalonDocument d : pending) {
            byCrit.merge(d.getCriticality(), 1, Integer::sum);
            Instant ca = d.getCreatedAt();
            if (ca != null && (oldest == null || ca.isBefore(oldest))) {
                oldest = ca;
            }
        }
        return new PendingSummary(pending.size(), byCrit, oldest);
    }

    // ────────────────── Mutations ──────────────────

    /**
     * Records an answer and transitions to {@link MaximegalonStatus#ANSWERED}.
     * Idempotent against double-submit: if status is already ANSWERED,
     * returns the existing item without overwriting.
     */
    public Optional<MaximegalonDocument> answer(
            String tenantId, String itemId,
            AnswerPayload answer, ResolvedBy by) {
        Optional<MaximegalonDocument> existing = findById(tenantId, itemId);
        if (existing.isEmpty()) return Optional.empty();
        MaximegalonDocument doc = existing.get();
        if (doc.getStatus() != MaximegalonStatus.PENDING) {
            log.info("Inbox.answer skipped — id='{}' already in status {}",
                    itemId, doc.getStatus());
            return Optional.of(doc);
        }
        Instant now = Instant.now();
        Update update = new Update()
                .set("status", MaximegalonStatus.ANSWERED)
                .set("answer", answer)
                .set("resolvedBy", by)
                .set("resolvedAt", now)
                .push("history", MaximegalonHistoryEntry.builder()
                        .action("ANSWERED")
                        .actor(answer.getAnsweredBy())
                        .at(now)
                        .build());
        UpdateResult result = mongoTemplate.updateFirst(
                Query.query(Criteria.where(F_ID).is(itemId).and(F_TENANT).is(tenantId)
                        .and(F_STATUS).is(MaximegalonStatus.PENDING)),
                update, MaximegalonDocument.class);
        if (result.getModifiedCount() == 0) {
            // Race: someone else answered first. Re-read.
            return findById(tenantId, itemId);
        }
        MaximegalonDocument refreshed = findById(tenantId, itemId).orElse(doc);
        // Server-side effect (permission request, kit install, …) before the
        // process is notified: the effect IS the decision's consequence,
        // while the process notification is only information about it.
        // Riding the same single PENDING→ANSWERED transition makes the
        // existing double-submit guard the effect's exactly-once guarantee.
        runEffect(refreshed, answer);
        eventPublisher.publishEvent(new MaximegalonAnsweredEvent(refreshed));
        return Optional.of(refreshed);
    }

    /**
     * Dispatches the item's {@link InboxEffect}, if it declares one.
     *
     * <p>A failing effect must not undo the answer — the human decided,
     * and losing that is worse than a failed side-effect. So the failure
     * is recorded on the item (visible in the UI and in the audit trail)
     * and swallowed: the answering client still gets a clean response,
     * and the mismatch between "approved" and "nothing happened" is
     * traceable rather than silent.
     */
    private void runEffect(MaximegalonDocument item, AnswerPayload answer) {
        try {
            effectRegistry.dispatch(item, answer);
        } catch (RuntimeException e) {
            log.error("Inbox effect failed for item '{}' (type='{}') — item stays ANSWERED",
                    item.getId(), item.getEffectType(), e);
            recordEffectFailure(item, answer, e);
        }
    }

    private void recordEffectFailure(
            MaximegalonDocument item, AnswerPayload answer, RuntimeException cause) {
        try {
            mongoTemplate.updateFirst(
                    Query.query(Criteria.where(F_ID).is(item.getId())
                            .and(F_TENANT).is(item.getTenantId())),
                    new Update().push("history", MaximegalonHistoryEntry.builder()
                            .action("EFFECT_FAILED")
                            .actor(answer.getAnsweredBy())
                            .at(Instant.now())
                            .details(cause.getMessage())
                            .build()),
                    MaximegalonDocument.class);
        } catch (RuntimeException e) {
            // Nothing left to do — the error log above is the record.
            log.warn("Could not record effect failure on item '{}': {}",
                    item.getId(), e.toString());
        }
    }

    public Optional<MaximegalonDocument> dismiss(
            String tenantId, String itemId, String byUserId) {
        return transitionTo(tenantId, itemId, MaximegalonStatus.DISMISSED, byUserId, "DISMISSED");
    }

    public Optional<MaximegalonDocument> archive(
            String tenantId, String itemId, String byUserId) {
        Optional<MaximegalonDocument> existing = findById(tenantId, itemId);
        if (existing.isEmpty()) return Optional.empty();
        MaximegalonDocument doc = existing.get();
        if (doc.getStatus() == MaximegalonStatus.ARCHIVED) return Optional.of(doc);
        Instant now = Instant.now();
        Update update = new Update()
                .set("status", MaximegalonStatus.ARCHIVED)
                .set("archivedAt", now)
                .push("history", MaximegalonHistoryEntry.builder()
                        .action("ARCHIVED")
                        .actor(byUserId)
                        .at(now)
                        .build());
        mongoTemplate.updateFirst(
                Query.query(Criteria.where(F_ID).is(itemId).and(F_TENANT).is(tenantId)),
                update, MaximegalonDocument.class);
        MaximegalonDocument refreshed = findById(tenantId, itemId).orElse(doc);
        eventPublisher.publishEvent(new MaximegalonArchivedEvent(refreshed));
        return Optional.of(refreshed);
    }

    /**
     * Pulls an archived item back into the active inbox. Status is
     * restored to {@link MaximegalonStatus#ANSWERED} when an answer
     * is on file (the item was answered before being archived) or
     * {@link MaximegalonStatus#PENDING} otherwise. {@code archivedAt}
     * is cleared. No-op when the item isn't currently archived.
     *
     * <p>v1 doesn't preserve the original pre-archive status — the
     * answer-presence heuristic gives the right answer in practice
     * (archive of an open ask vs. archive of a resolved item).
     */
    public Optional<MaximegalonDocument> unarchive(
            String tenantId, String itemId, String byUserId) {
        Optional<MaximegalonDocument> existing = findById(tenantId, itemId);
        if (existing.isEmpty()) return Optional.empty();
        MaximegalonDocument doc = existing.get();
        if (doc.getStatus() != MaximegalonStatus.ARCHIVED) return Optional.of(doc);
        MaximegalonStatus restored = doc.getAnswer() != null
                ? MaximegalonStatus.ANSWERED : MaximegalonStatus.PENDING;
        Instant now = Instant.now();
        Update update = new Update()
                .set("status", restored)
                .unset("archivedAt")
                .push("history", MaximegalonHistoryEntry.builder()
                        .action("UNARCHIVED")
                        .actor(byUserId)
                        .details("restored to " + restored.name())
                        .at(now)
                        .build());
        mongoTemplate.updateFirst(
                Query.query(Criteria.where(F_ID).is(itemId).and(F_TENANT).is(tenantId)),
                update, MaximegalonDocument.class);
        return findById(tenantId, itemId);
    }

    /**
     * In-place patch of an existing item's body, optional title and
     * payload. Status, ownership and history-of-prior-actions stay
     * untouched; a {@code CONTENT_UPDATED} history entry is appended.
     * Intended for system components that track an evolving event
     * with a single inbox-item (e.g. Fook upstream-transfer).
     *
     * <p>Returns {@code empty} if no item with that id exists in the
     * tenant. {@code newPayload} replaces the entire payload map;
     * pass {@code null} to keep the current one.
     */
    public Optional<MaximegalonDocument> updateContent(
            String tenantId,
            String itemId,
            @Nullable String newTitle,
            String newBody,
            @Nullable Map<String, Object> newPayload,
            String byActorId) {
        Optional<MaximegalonDocument> existing = findById(tenantId, itemId);
        if (existing.isEmpty()) return Optional.empty();
        MaximegalonDocument doc = existing.get();
        Instant now = Instant.now();
        Update update = new Update()
                .set("body", newBody)
                .push("history", MaximegalonHistoryEntry.builder()
                        .action("CONTENT_UPDATED")
                        .actor(byActorId)
                        .at(now)
                        .build());
        if (newTitle != null && !newTitle.equals(doc.getTitle())) {
            update.set("title", newTitle);
        }
        if (newPayload != null) {
            update.set("payload", newPayload);
        }
        mongoTemplate.updateFirst(
                Query.query(Criteria.where(F_ID).is(itemId).and(F_TENANT).is(tenantId)),
                update, MaximegalonDocument.class);
        MaximegalonDocument refreshed = findById(tenantId, itemId).orElse(doc);
        eventPublisher.publishEvent(new MaximegalonUpdatedEvent(refreshed));
        return Optional.of(refreshed);
    }

    public Optional<MaximegalonDocument> delegate(
            String tenantId, String itemId, String toUserId, String byUserId,
            @Nullable String note) {
        Optional<MaximegalonDocument> existing = findById(tenantId, itemId);
        if (existing.isEmpty()) return Optional.empty();
        MaximegalonDocument doc = existing.get();
        if (toUserId.equals(doc.getAssignedToUserId())) {
            // No-op delegation.
            return Optional.of(doc);
        }
        String previous = doc.getAssignedToUserId();
        Instant now = Instant.now();
        Update update = new Update()
                .set(F_ASSIGNED, toUserId)
                // The new assignee joins and is told; the previous one stays a
                // participant, so delegating no longer loses sight of the matter.
                // With teamId set, visibility does not move at all — which is
                // what makes "delegation changes who is up, not who can see"
                // true rather than merely intended.
                .addToSet(F_PARTICIPANTS, toUserId)
                .addToSet(F_UNREAD_FOR, toUserId)
                .push("history", MaximegalonHistoryEntry.builder()
                        .action("DELEGATED")
                        .actor(byUserId)
                        .details("from=" + previous + " to=" + toUserId
                                + (note == null ? "" : " note=" + note))
                        .at(now)
                        .build());
        mongoTemplate.updateFirst(
                Query.query(Criteria.where(F_ID).is(itemId).and(F_TENANT).is(tenantId)),
                update, MaximegalonDocument.class);
        MaximegalonDocument refreshed = findById(tenantId, itemId).orElse(doc);
        eventPublisher.publishEvent(new MaximegalonDelegatedEvent(refreshed, previous));
        return Optional.of(refreshed);
    }

    private Optional<MaximegalonDocument> transitionTo(
            String tenantId, String itemId,
            MaximegalonStatus newStatus, String byUserId, String historyAction) {
        Optional<MaximegalonDocument> existing = findById(tenantId, itemId);
        if (existing.isEmpty()) return Optional.empty();
        MaximegalonDocument doc = existing.get();
        if (doc.getStatus() == newStatus) return Optional.of(doc);
        Instant now = Instant.now();
        Update update = new Update()
                .set("status", newStatus)
                .push("history", MaximegalonHistoryEntry.builder()
                        .action(historyAction)
                        .actor(byUserId)
                        .at(now)
                        .build());
        if (newStatus == MaximegalonStatus.DISMISSED) {
            update.set("resolvedBy", ResolvedBy.USER).set("resolvedAt", now);
        }
        mongoTemplate.updateFirst(
                Query.query(Criteria.where(F_ID).is(itemId).and(F_TENANT).is(tenantId)),
                update, MaximegalonDocument.class);
        return findById(tenantId, itemId);
    }

    // ────────────────── Thread: messages ──────────────────

    /**
     * Appends a contribution and marks the thread unread for everyone else.
     *
     * <p><b>One write.</b> Pushing the message and updating {@link
     * MaximegalonDocument#getUnreadFor()} happen in the same update — the
     * reason the discussion is embedded rather than a second collection. Split
     * across two collections this would need a transaction, or the index could
     * drift from the truth it indexes.
     *
     * <p>The author joins {@code participants} implicitly: contributing is a
     * way of taking part. Their own message starts read for them, and their
     * membership in {@code unreadFor} is left alone — they may well have older
     * contributions they never opened.
     *
     * @param parentId the message being replied to, or {@code null} for the
     *                 root level (a reply to the thread's own question)
     * @throws MaximegalonRuleException {@code MESSAGE_LIMIT_REACHED} or
     *                                  {@code INVALID_PARENT}
     */
    public Optional<MaximegalonDocument> postMessage(
            String tenantId, String itemId, String authorUserId, String body,
            @Nullable String parentId) {
        Optional<MaximegalonDocument> existing = findById(tenantId, itemId);
        if (existing.isEmpty()) return Optional.empty();
        MaximegalonDocument doc = existing.get();

        List<MaximegalonMessage> current = doc.getMessages() == null
                ? List.<MaximegalonMessage>of() : doc.getMessages();
        if (current.size() >= MAX_MESSAGES) {
            throw new MaximegalonRuleException(
                    MaximegalonRuleException.MESSAGE_LIMIT_REACHED,
                    "thread '" + itemId + "' already holds " + current.size()
                            + " messages (limit " + MAX_MESSAGES + ")");
        }
        if (parentId != null) {
            requireReplyableParent(current, parentId, itemId);
        }

        Instant now = Instant.now();
        MaximegalonMessage message = MaximegalonMessage.builder()
                .id(new ObjectId().toHexString())
                .authorUserId(authorUserId)
                .body(body)
                .createdAt(now)
                .parentId(parentId)
                .readBy(new ArrayList<>(List.of(authorUserId)))
                .reactions(new ArrayList<>())
                .build();

        List<String> nowUnread = new ArrayList<>();
        for (String p : doc.getParticipants()) {
            if (!p.equals(authorUserId)) nowUnread.add(p);
        }

        Update update = new Update()
                .push(F_MESSAGES, message)
                .addToSet(F_PARTICIPANTS, authorUserId);
        if (!nowUnread.isEmpty()) {
            update.addToSet(F_UNREAD_FOR).each(nowUnread.toArray());
        }
        mongoTemplate.updateFirst(byId(tenantId, itemId), update, MaximegalonDocument.class);
        return findById(tenantId, itemId);
    }

    /**
     * Depth is capped at one level, so the parent must exist and must itself be
     * a root message. Checked here rather than in the schema — see
     * {@link MaximegalonMessage} for why that is deliberate.
     */
    private static void requireReplyableParent(
            List<MaximegalonMessage> messages, String parentId, String itemId) {
        for (MaximegalonMessage m : messages) {
            if (parentId.equals(m.getId())) {
                if (m.getParentId() != null) {
                    throw new MaximegalonRuleException(
                            MaximegalonRuleException.INVALID_PARENT,
                            "message '" + parentId + "' is itself a reply; depth is capped at one");
                }
                return;
            }
        }
        throw new MaximegalonRuleException(
                MaximegalonRuleException.INVALID_PARENT,
                "no message '" + parentId + "' in thread '" + itemId + "'");
    }

    // ────────────────── Thread: read state ──────────────────

    /**
     * Marks the whole thread read for one user — body and every message — and
     * drops them from the badge index. One update, using the all-positional
     * operator, so opening a thread with fifty messages is one write.
     *
     * <p><b>Reading never closes an ask.</b> {@code status} is untouched:
     * looking at a decision is not making it. That separation is the point of
     * having read as an axis of its own (§3a).
     */
    public Optional<MaximegalonDocument> markRead(String tenantId, String itemId, String userId) {
        Optional<MaximegalonDocument> existing = findById(tenantId, itemId);
        if (existing.isEmpty()) return Optional.empty();
        Update update = new Update()
                .addToSet(F_READ_BY, userId)
                .pull(F_UNREAD_FOR, userId);
        if (existing.get().getMessages() != null && !existing.get().getMessages().isEmpty()) {
            update.addToSet(F_MESSAGES + ".$[].readBy", userId);
        }
        mongoTemplate.updateFirst(byId(tenantId, itemId), update, MaximegalonDocument.class);
        return findById(tenantId, itemId);
    }

    /**
     * Marks individual messages read — the deep-link case, where someone lands
     * on message five without having seen three and four. This is why read
     * state sits per message and not as a single watermark on the thread: a
     * watermark would silently tick off everything before the target.
     *
     * <p>{@code unreadFor} is only cleared when nothing is left open for that
     * user, which needs a re-read; that is the cost of partial reading and the
     * reason {@link #markRead} exists as the cheap common case.
     */
    public Optional<MaximegalonDocument> markMessagesRead(
            String tenantId, String itemId, String userId, List<String> messageIds) {
        if (messageIds.isEmpty()) return findById(tenantId, itemId);
        Optional<MaximegalonDocument> existing = findById(tenantId, itemId);
        if (existing.isEmpty()) return Optional.empty();

        mongoTemplate.updateFirst(
                byId(tenantId, itemId),
                new Update().addToSet(F_MESSAGES + ".$[m].readBy", userId)
                        .filterArray(Criteria.where("m.id").in(messageIds)),
                MaximegalonDocument.class);

        MaximegalonDocument refreshed = findById(tenantId, itemId).orElse(existing.get());
        if (!hasUnreadFor(refreshed, userId)) {
            mongoTemplate.updateFirst(byId(tenantId, itemId),
                    new Update().pull(F_UNREAD_FOR, userId), MaximegalonDocument.class);
            return findById(tenantId, itemId);
        }
        return Optional.of(refreshed);
    }

    /** The definition {@code unreadFor} indexes — also the repair rule. */
    private static boolean hasUnreadFor(MaximegalonDocument doc, String userId) {
        if (doc.getReadBy() == null || !doc.getReadBy().contains(userId)) return true;
        if (doc.getMessages() == null) return false;
        for (MaximegalonMessage m : doc.getMessages()) {
            if (m.getReadBy() == null || !m.getReadBy().contains(userId)) return true;
        }
        return false;
    }

    // ────────────────── Thread: participation ──────────────────

    /**
     * Adds someone to the thread and makes it unread for them.
     *
     * <p><b>An invitation creates unread, joining does not</b> ({@link
     * #setFollowing}): being pulled in by someone else has to be noticeable,
     * whereas whoever subscribes themselves is looking at the thread already.
     *
     * <p>Authorization is the caller's: inviting <em>is</em> delivering, so it
     * goes through the same {@code Resource.InboxItem} + {@code WRITE} check
     * that Milliways' inbox handler uses. Participation itself is a property of
     * the object, not a grant — it is checked on entry, not on every access.
     */
    public Optional<MaximegalonDocument> invite(
            String tenantId, String itemId, String invitedUserId, String byUserId) {
        Optional<MaximegalonDocument> existing = findById(tenantId, itemId);
        if (existing.isEmpty()) return Optional.empty();
        mongoTemplate.updateFirst(byId(tenantId, itemId),
                new Update()
                        .addToSet(F_PARTICIPANTS, invitedUserId)
                        .addToSet(F_UNREAD_FOR, invitedUserId)
                        .push("history", MaximegalonHistoryEntry.builder()
                                .action("INVITED")
                                .actor(byUserId)
                                .details("invited=" + invitedUserId)
                                .at(Instant.now())
                                .build()),
                MaximegalonDocument.class);
        return findById(tenantId, itemId);
    }

    /**
     * Takes someone out of the thread again — the counterpart to
     * {@link #invite} and to a self-join through {@link #setFollowing}.
     *
     * <p><b>Why this has to exist.</b> {@code participants} is checked first in
     * {@code InboxAuthz.maySee}, so joining converts a <em>derived</em>
     * visibility (sharing a team with whoever is currently assigned) into a
     * <em>permanent</em> one. After a delegation out of that team the entry
     * remains and so does the access. Without a removal path only the person
     * themselves could undo that, which means the thread's owner has no answer
     * to an unwanted join at all.
     *
     * <p>Authorization is the caller's, and it is {@code mayDecide}, not
     * {@code maySee}: deciding who is in the room is part of running the
     * matter, and a participant must not be able to remove another participant.
     *
     * <p>Removing also clears the badge — leaving someone in {@code unreadFor}
     * for a thread they can no longer open would light a badge that cannot be
     * cleared.
     *
     * @throws MaximegalonRuleException {@code PARTICIPANT_MUST_STAY} for the
     *         assignee of an open ask (a process is waiting on them; delegate
     *         instead) and for the originator (the thread's audit record)
     */
    public Optional<MaximegalonDocument> removeParticipant(
            String tenantId, String itemId, String userId, String byUserId) {
        Optional<MaximegalonDocument> existing = findById(tenantId, itemId);
        if (existing.isEmpty()) return Optional.empty();
        MaximegalonDocument doc = existing.get();

        if (userId.equals(doc.getAssignedToUserId())
                && doc.isRequiresAction()
                && doc.getStatus() == MaximegalonStatus.PENDING) {
            throw new MaximegalonRuleException(
                    MaximegalonRuleException.PARTICIPANT_MUST_STAY,
                    "user '" + userId + "' is the assignee of the open ask '" + itemId
                            + "' — delegate instead of removing them");
        }
        if (userId.equals(doc.getOriginatorUserId())) {
            throw new MaximegalonRuleException(
                    MaximegalonRuleException.PARTICIPANT_MUST_STAY,
                    "user '" + userId + "' opened thread '" + itemId
                            + "' — the originator is not removable");
        }

        mongoTemplate.updateFirst(byId(tenantId, itemId),
                new Update()
                        .pull(F_PARTICIPANTS, userId)
                        .pull(F_UNREAD_FOR, userId)
                        .push("history", MaximegalonHistoryEntry.builder()
                                .action("REMOVED")
                                .actor(byUserId)
                                .details("removed=" + userId)
                                .at(Instant.now())
                                .build()),
                MaximegalonDocument.class);
        return findById(tenantId, itemId);
    }

    /**
     * Subscribes to or unsubscribes from a thread's updates.
     *
     * <p>Unsubscribing drops the user from {@code unreadFor} too — leaving them
     * there would keep a badge alight for a thread they asked to be rid of.
     * Subscribing does <em>not</em> add unread (see {@link #invite}).
     *
     * @throws MaximegalonRuleException {@code ASSIGNEE_MUST_STAY} when the
     *         assignee of an open ask tries to leave — a process is waiting on
     *         them; delegation is the way out
     */
    public Optional<MaximegalonDocument> setFollowing(
            String tenantId, String itemId, String userId, boolean following) {
        Optional<MaximegalonDocument> existing = findById(tenantId, itemId);
        if (existing.isEmpty()) return Optional.empty();
        MaximegalonDocument doc = existing.get();

        if (!following
                && userId.equals(doc.getAssignedToUserId())
                && doc.isRequiresAction()
                && doc.getStatus() == MaximegalonStatus.PENDING) {
            throw new MaximegalonRuleException(
                    MaximegalonRuleException.ASSIGNEE_MUST_STAY,
                    "user '" + userId + "' is the assignee of the open ask '" + itemId
                            + "' — delegate instead of unsubscribing");
        }

        Update update = following
                ? new Update().addToSet(F_PARTICIPANTS, userId)
                : new Update().pull(F_PARTICIPANTS, userId).pull(F_UNREAD_FOR, userId);
        mongoTemplate.updateFirst(byId(tenantId, itemId), update, MaximegalonDocument.class);
        return findById(tenantId, itemId);
    }

    // ────────────────── Thread: reactions ──────────────────

    /**
     * Toggles one user's emoji reaction on the thread body
     * ({@code messageId == null}) or on a single message.
     *
     * <p>Read-modify-write guarded by {@code @Version}: a reaction rewrites an
     * array of objects, which {@code $addToSet} cannot address by key without
     * knowing whether the entry exists yet. A lost race here costs one click,
     * so a single retry is enough and a failed second attempt is dropped
     * rather than escalated.
     *
     * <p>Reactions never touch {@code unreadFor}: they are the quiet channel.
     * Five agreements must not ring five bells — whoever wants to be loud
     * writes a message.
     */
    public Optional<MaximegalonDocument> react(
            String tenantId, String itemId, @Nullable String messageId,
            String key, String userId, boolean on) {
        for (int attempt = 0; attempt < 2; attempt++) {
            Optional<MaximegalonDocument> existing = findById(tenantId, itemId);
            if (existing.isEmpty()) return Optional.empty();
            MaximegalonDocument doc = existing.get();

            Update update = new Update();
            if (messageId == null) {
                update.set(F_REACTIONS, toggled(doc.getReactions(), key, userId, on));
            } else {
                MaximegalonMessage target = null;
                for (MaximegalonMessage m : doc.getMessages()) {
                    if (messageId.equals(m.getId())) { target = m; break; }
                }
                if (target == null) return Optional.of(doc);
                update.set(F_MESSAGES + ".$[m].reactions",
                                toggled(target.getReactions(), key, userId, on))
                        .filterArray(Criteria.where("m.id").is(messageId));
            }
            UpdateResult result = mongoTemplate.updateFirst(
                    Query.query(Criteria.where(F_ID).is(itemId).and(F_TENANT).is(tenantId)
                            .and("version").is(doc.getVersion())),
                    update, MaximegalonDocument.class);
            if (result.getModifiedCount() > 0) {
                return findById(tenantId, itemId);
            }
        }
        log.debug("Reaction '{}' on item '{}' lost two races — dropped", key, itemId);
        return findById(tenantId, itemId);
    }

    /**
     * Pure toggle on a reaction list: adds or removes one user under one key.
     *
     * @throws MaximegalonRuleException {@code REACTION_LIMIT_REACHED} when a
     *         <em>new</em> key would push the node past
     *         {@link #MAX_REACTION_KEYS}
     */
    private static List<MaximegalonReaction> toggled(
            @Nullable List<MaximegalonReaction> current, String key, String userId, boolean on) {
        if (on && current != null && current.size() >= MAX_REACTION_KEYS
                && !containsKey(current, key)) {
            throw new MaximegalonRuleException(
                    MaximegalonRuleException.REACTION_LIMIT_REACHED,
                    "already " + current.size() + " distinct reactions here (limit "
                            + MAX_REACTION_KEYS + ") — join one of them instead");
        }
        List<MaximegalonReaction> result = new ArrayList<>();
        boolean seen = false;
        if (current != null) {
            for (MaximegalonReaction r : current) {
                if (!key.equals(r.getKey())) {
                    result.add(r);
                    continue;
                }
                seen = true;
                List<String> users = new ArrayList<>(
                        r.getUserIds() == null ? List.<String>of() : r.getUserIds());
                if (on) {
                    if (!users.contains(userId)) users.add(userId);
                } else {
                    users.remove(userId);
                }
                // An empty reaction is dropped: a key with nobody behind it
                // would still render as a chip showing zero.
                if (!users.isEmpty()) {
                    result.add(MaximegalonReaction.builder().key(key).userIds(users).build());
                }
            }
        }
        if (!seen && on) {
            result.add(MaximegalonReaction.builder()
                    .key(key).userIds(new ArrayList<>(List.of(userId))).build());
        }
        return result;
    }

    /** Whether {@code key} already has an entry — a toggle on it is never new. */
    private static boolean containsKey(List<MaximegalonReaction> current, String key) {
        for (MaximegalonReaction r : current) {
            if (key.equals(r.getKey())) return true;
        }
        return false;
    }

    // ────────────────── Badge ──────────────────

    /**
     * The three numbers behind the topbar badge, each with exactly one reader.
     *
     * <p>{@code unread} is the count <em>in</em> the badge, {@code
     * unreadRequiresAction} its colour, {@code pending} the tooltip. Colour and
     * count must come from the same population — colouring on all open asks
     * while counting only unread threads would paint the badge red because
     * something is open somewhere, even when every unread thread is a harmless
     * output.
     *
     * <p><b>Only unread counts.</b> A decision deliberately held back — waiting
     * on information, wrong moment — must not glow forever: a badge that cannot
     * reach zero without deciding trains people to dismiss. The stock of open
     * matters is in the list and in the tooltip; the badge is an alarm, not an
     * inventory. See {@code planning/maximegalon.md} §4b.
     */
    public BadgeCounts countBadge(String tenantId, String userId) {
        Criteria notArchived = Criteria.where(F_TENANT).is(tenantId)
                .and(F_UNREAD_FOR).is(userId)
                .and(F_STATUS).ne(MaximegalonStatus.ARCHIVED);
        long unread = mongoTemplate.count(Query.query(notArchived), MaximegalonDocument.class);

        long unreadRequiresAction = mongoTemplate.count(
                Query.query(Criteria.where(F_TENANT).is(tenantId)
                        .and(F_UNREAD_FOR).is(userId)
                        .and(F_STATUS).is(MaximegalonStatus.PENDING)
                        .and(F_REQUIRES_ACTION).is(true)
                        .and(F_ASSIGNED).is(userId)),
                MaximegalonDocument.class);

        long pending = mongoTemplate.count(
                Query.query(filterCriteria(tenantId, List.of(userId),
                        MaximegalonStatus.PENDING, null)),
                MaximegalonDocument.class);

        return new BadgeCounts(unread, unreadRequiresAction, pending);
    }

    /**
     * Pending-item counts for the topbar badge — {@code total} is everything
     * still pending, {@code requiresAction} the subset that waits on an answer.
     */
    public record PendingCounts(long total, long requiresAction) {}

    /**
     * Badge numbers: {@code unread} is shown, {@code unreadRequiresAction}
     * colours it, {@code pending} is the stock behind the tooltip.
     */
    public record BadgeCounts(long unread, long unreadRequiresAction, long pending) {}

    /** Lightweight summary used by {@code inbox-pending-summary} on session resume. */
    public record PendingSummary(
            int totalPending,
            Map<Criticality, Integer> byCriticality,
            @Nullable Instant oldestPendingAt) {}
}
