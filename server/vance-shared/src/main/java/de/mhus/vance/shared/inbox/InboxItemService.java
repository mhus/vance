package de.mhus.vance.shared.inbox;

import com.mongodb.client.result.UpdateResult;
import de.mhus.vance.api.inbox.AnswerOutcome;
import de.mhus.vance.api.inbox.AnswerPayload;
import de.mhus.vance.api.inbox.Criticality;
import de.mhus.vance.api.inbox.InboxItemStatus;
import de.mhus.vance.api.inbox.InboxItemType;
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
 * Lifecycle and lookup for {@link InboxItemDocument}. The single
 * entry point to inbox data — analogous to
 * {@code ThinkProcessService} for processes.
 *
 * <h2>Auto-default for LOW criticality</h2>
 * Items with {@link Criticality#LOW} <em>and</em> a {@code default}
 * key in their payload are auto-answered at creation time:
 * {@link InboxItemStatus#ANSWERED}, {@link ResolvedBy#AUTO_DEFAULT},
 * answer carries the default. The originating process gets the
 * answer immediately via the steer-message path; no user is
 * bothered. The item is still persisted with the audit marker.
 *
 * <h2>Events</h2>
 * Publishes Spring application events on key transitions —
 * {@link InboxItemCreatedEvent}, {@link InboxItemAnsweredEvent},
 * {@link InboxItemDelegatedEvent}, {@link InboxItemArchivedEvent}.
 * The notification dispatcher subscribes to {@code Created} and
 * {@code Delegated} (new assignee gets pinged); the steer-router
 * subscribes to {@code Answered}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InboxItemService {

    private static final String F_ID = "_id";
    private static final String F_TENANT = "tenantId";
    private static final String F_STATUS = "status";
    private static final String F_ASSIGNED = "assignedToUserId";

    /** Conventional payload key for the LOW-auto-default value. */
    public static final String PAYLOAD_DEFAULT_KEY = "default";

    private final InboxItemRepository repository;
    private final MongoTemplate mongoTemplate;
    private final ApplicationEventPublisher eventPublisher;

    // ────────────────── Create ──────────────────

    /**
     * Persists a new item. If {@link Criticality#LOW} and the
     * payload contains a {@code default}, the item is created
     * already in {@code ANSWERED} state with
     * {@link ResolvedBy#AUTO_DEFAULT}.
     *
     * @return the saved item (with id assigned)
     */
    public InboxItemDocument create(InboxItemDocument toCreate) {
        Instant now = Instant.now();
        boolean autoAnswered = shouldAutoAnswer(toCreate);

        // Always start with a CREATED history entry.
        List<InboxItemHistoryEntry> history = toCreate.getHistory();
        if (history == null) history = new ArrayList<>();
        history.add(InboxItemHistoryEntry.builder()
                .action("CREATED")
                .actor(toCreate.getOriginatorUserId())
                .at(now)
                .build());

        if (autoAnswered) {
            Object defaultValue = toCreate.getPayload().get(PAYLOAD_DEFAULT_KEY);
            Map<String, Object> answerValue = wrapDefaultAsAnswerValue(
                    toCreate.getType(), defaultValue);
            toCreate.setStatus(InboxItemStatus.ANSWERED);
            toCreate.setAnswer(AnswerPayload.builder()
                    .outcome(AnswerOutcome.DECIDED)
                    .value(answerValue)
                    .answeredBy("system:auto-default")
                    .build());
            toCreate.setResolvedBy(ResolvedBy.AUTO_DEFAULT);
            toCreate.setResolvedAt(now);
            history.add(InboxItemHistoryEntry.builder()
                    .action("ANSWERED")
                    .actor("system:auto-default")
                    .details("LOW criticality auto-default")
                    .at(now)
                    .build());
        }
        toCreate.setHistory(history);

        InboxItemDocument saved = repository.save(toCreate);
        log.info("Created inbox item id='{}' tenant='{}' assignee='{}' type={} crit={} requiresAction={} status={}",
                saved.getId(), saved.getTenantId(), saved.getAssignedToUserId(),
                saved.getType(), saved.getCriticality(),
                saved.isRequiresAction(), saved.getStatus());

        eventPublisher.publishEvent(new InboxItemCreatedEvent(saved));
        if (autoAnswered) {
            eventPublisher.publishEvent(new InboxItemAnsweredEvent(saved));
        }
        return saved;
    }

    /**
     * Decision rule: auto-answer iff item is asking ({@code requiresAction}),
     * criticality is LOW, and payload carries a {@code default}.
     */
    private static boolean shouldAutoAnswer(InboxItemDocument doc) {
        if (!doc.isRequiresAction()) return false;
        if (doc.getCriticality() != Criticality.LOW) return false;
        if (doc.getPayload() == null) return false;
        return doc.getPayload().containsKey(PAYLOAD_DEFAULT_KEY);
    }

    /** Maps the bare {@code default} value to the type-shaped answer-value map. */
    private static Map<String, Object> wrapDefaultAsAnswerValue(
            InboxItemType type, Object defaultValue) {
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

    public Optional<InboxItemDocument> findById(String tenantId, String id) {
        return repository.findByIdAndTenantId(id, tenantId);
    }

    public List<InboxItemDocument> listForUser(
            String tenantId, String userId, @Nullable InboxItemStatus status) {
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
    public List<InboxItemDocument> listFiltered(
            String tenantId,
            List<String> userIds,
            @Nullable InboxItemStatus status,
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
        Query query = Query.query(criteria)
                .with(Sort.by(Sort.Direction.DESC, "createdAt"));
        return mongoTemplate.find(query, InboxItemDocument.class);
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
                Query.query(criteria), "tags", InboxItemDocument.class, String.class);
    }

    public List<InboxItemDocument> listPendingForUser(String tenantId, String userId) {
        return listForUser(tenantId, userId, InboxItemStatus.PENDING);
    }

    public PendingSummary summarizePendingForUser(String tenantId, String userId) {
        List<InboxItemDocument> pending = listPendingForUser(tenantId, userId);
        Map<Criticality, Integer> byCrit = new LinkedHashMap<>();
        for (Criticality c : Criticality.values()) byCrit.put(c, 0);
        Instant oldest = null;
        for (InboxItemDocument d : pending) {
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
     * Records an answer and transitions to {@link InboxItemStatus#ANSWERED}.
     * Idempotent against double-submit: if status is already ANSWERED,
     * returns the existing item without overwriting.
     */
    public Optional<InboxItemDocument> answer(
            String tenantId, String itemId,
            AnswerPayload answer, ResolvedBy by) {
        Optional<InboxItemDocument> existing = findById(tenantId, itemId);
        if (existing.isEmpty()) return Optional.empty();
        InboxItemDocument doc = existing.get();
        if (doc.getStatus() != InboxItemStatus.PENDING) {
            log.info("Inbox.answer skipped — id='{}' already in status {}",
                    itemId, doc.getStatus());
            return Optional.of(doc);
        }
        Instant now = Instant.now();
        Update update = new Update()
                .set("status", InboxItemStatus.ANSWERED)
                .set("answer", answer)
                .set("resolvedBy", by)
                .set("resolvedAt", now)
                .push("history", InboxItemHistoryEntry.builder()
                        .action("ANSWERED")
                        .actor(answer.getAnsweredBy())
                        .at(now)
                        .build());
        UpdateResult result = mongoTemplate.updateFirst(
                Query.query(Criteria.where(F_ID).is(itemId).and(F_TENANT).is(tenantId)
                        .and(F_STATUS).is(InboxItemStatus.PENDING)),
                update, InboxItemDocument.class);
        if (result.getModifiedCount() == 0) {
            // Race: someone else answered first. Re-read.
            return findById(tenantId, itemId);
        }
        InboxItemDocument refreshed = findById(tenantId, itemId).orElse(doc);
        eventPublisher.publishEvent(new InboxItemAnsweredEvent(refreshed));
        return Optional.of(refreshed);
    }

    public Optional<InboxItemDocument> dismiss(
            String tenantId, String itemId, String byUserId) {
        return transitionTo(tenantId, itemId, InboxItemStatus.DISMISSED, byUserId, "DISMISSED");
    }

    public Optional<InboxItemDocument> archive(
            String tenantId, String itemId, String byUserId) {
        Optional<InboxItemDocument> existing = findById(tenantId, itemId);
        if (existing.isEmpty()) return Optional.empty();
        InboxItemDocument doc = existing.get();
        if (doc.getStatus() == InboxItemStatus.ARCHIVED) return Optional.of(doc);
        Instant now = Instant.now();
        Update update = new Update()
                .set("status", InboxItemStatus.ARCHIVED)
                .set("archivedAt", now)
                .push("history", InboxItemHistoryEntry.builder()
                        .action("ARCHIVED")
                        .actor(byUserId)
                        .at(now)
                        .build());
        mongoTemplate.updateFirst(
                Query.query(Criteria.where(F_ID).is(itemId).and(F_TENANT).is(tenantId)),
                update, InboxItemDocument.class);
        InboxItemDocument refreshed = findById(tenantId, itemId).orElse(doc);
        eventPublisher.publishEvent(new InboxItemArchivedEvent(refreshed));
        return Optional.of(refreshed);
    }

    /**
     * Pulls an archived item back into the active inbox. Status is
     * restored to {@link InboxItemStatus#ANSWERED} when an answer
     * is on file (the item was answered before being archived) or
     * {@link InboxItemStatus#PENDING} otherwise. {@code archivedAt}
     * is cleared. No-op when the item isn't currently archived.
     *
     * <p>v1 doesn't preserve the original pre-archive status — the
     * answer-presence heuristic gives the right answer in practice
     * (archive of an open ask vs. archive of a resolved item).
     */
    public Optional<InboxItemDocument> unarchive(
            String tenantId, String itemId, String byUserId) {
        Optional<InboxItemDocument> existing = findById(tenantId, itemId);
        if (existing.isEmpty()) return Optional.empty();
        InboxItemDocument doc = existing.get();
        if (doc.getStatus() != InboxItemStatus.ARCHIVED) return Optional.of(doc);
        InboxItemStatus restored = doc.getAnswer() != null
                ? InboxItemStatus.ANSWERED : InboxItemStatus.PENDING;
        Instant now = Instant.now();
        Update update = new Update()
                .set("status", restored)
                .unset("archivedAt")
                .push("history", InboxItemHistoryEntry.builder()
                        .action("UNARCHIVED")
                        .actor(byUserId)
                        .details("restored to " + restored.name())
                        .at(now)
                        .build());
        mongoTemplate.updateFirst(
                Query.query(Criteria.where(F_ID).is(itemId).and(F_TENANT).is(tenantId)),
                update, InboxItemDocument.class);
        return findById(tenantId, itemId);
    }

    public Optional<InboxItemDocument> delegate(
            String tenantId, String itemId, String toUserId, String byUserId,
            @Nullable String note) {
        Optional<InboxItemDocument> existing = findById(tenantId, itemId);
        if (existing.isEmpty()) return Optional.empty();
        InboxItemDocument doc = existing.get();
        if (toUserId.equals(doc.getAssignedToUserId())) {
            // No-op delegation.
            return Optional.of(doc);
        }
        String previous = doc.getAssignedToUserId();
        Instant now = Instant.now();
        Update update = new Update()
                .set(F_ASSIGNED, toUserId)
                .push("history", InboxItemHistoryEntry.builder()
                        .action("DELEGATED")
                        .actor(byUserId)
                        .details("from=" + previous + " to=" + toUserId
                                + (note == null ? "" : " note=" + note))
                        .at(now)
                        .build());
        mongoTemplate.updateFirst(
                Query.query(Criteria.where(F_ID).is(itemId).and(F_TENANT).is(tenantId)),
                update, InboxItemDocument.class);
        InboxItemDocument refreshed = findById(tenantId, itemId).orElse(doc);
        eventPublisher.publishEvent(new InboxItemDelegatedEvent(refreshed, previous));
        return Optional.of(refreshed);
    }

    private Optional<InboxItemDocument> transitionTo(
            String tenantId, String itemId,
            InboxItemStatus newStatus, String byUserId, String historyAction) {
        Optional<InboxItemDocument> existing = findById(tenantId, itemId);
        if (existing.isEmpty()) return Optional.empty();
        InboxItemDocument doc = existing.get();
        if (doc.getStatus() == newStatus) return Optional.of(doc);
        Instant now = Instant.now();
        Update update = new Update()
                .set("status", newStatus)
                .push("history", InboxItemHistoryEntry.builder()
                        .action(historyAction)
                        .actor(byUserId)
                        .at(now)
                        .build());
        if (newStatus == InboxItemStatus.DISMISSED) {
            update.set("resolvedBy", ResolvedBy.USER).set("resolvedAt", now);
        }
        mongoTemplate.updateFirst(
                Query.query(Criteria.where(F_ID).is(itemId).and(F_TENANT).is(tenantId)),
                update, InboxItemDocument.class);
        return findById(tenantId, itemId);
    }

    /** Lightweight summary used by {@code inbox-pending-summary} on session resume. */
    public record PendingSummary(
            int totalPending,
            Map<Criticality, Integer> byCriticality,
            @Nullable Instant oldestPendingAt) {}
}
