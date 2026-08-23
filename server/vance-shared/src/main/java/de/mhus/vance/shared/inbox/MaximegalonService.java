package de.mhus.vance.shared.inbox;

import com.mongodb.client.result.UpdateResult;
import de.mhus.vance.api.inbox.AnswerOutcome;
import de.mhus.vance.api.inbox.AnswerPayload;
import de.mhus.vance.api.inbox.Criticality;
import de.mhus.vance.api.inbox.MaximegalonStatus;
import de.mhus.vance.api.inbox.MaximegalonType;
import de.mhus.vance.api.inbox.ResolvedBy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.MongoTemplate;
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
     *         the freshest land at the top.
     */
    public List<MaximegalonDocument> listFiltered(
            String tenantId,
            List<String> userIds,
            @Nullable MaximegalonStatus status,
            @Nullable String tag) {
        Query query = Query.query(filterCriteria(tenantId, userIds, status, tag))
                .with(Sort.by(Sort.Direction.DESC, "createdAt"));
        return mongoTemplate.find(query, MaximegalonDocument.class);
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

    /**
     * Pending-item counts for the topbar badge — {@code total} is everything
     * still pending, {@code requiresAction} the subset that waits on an answer.
     */
    public record PendingCounts(long total, long requiresAction) {}

    /** Lightweight summary used by {@code inbox-pending-summary} on session resume. */
    public record PendingSummary(
            int totalPending,
            Map<Criticality, Integer> byCriticality,
            @Nullable Instant oldestPendingAt) {}
}
