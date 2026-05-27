package de.mhus.vance.shared.chat;

import com.mongodb.client.result.UpdateResult;
import de.mhus.vance.shared.session.SessionService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.TextCriteria;
import org.springframework.data.mongodb.core.query.TextQuery;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Chat-message lifecycle — append, read, cleanup. The single entry point to
 * chat-message data.
 *
 * <p>{@link #history} returns every message in chronological order, archive
 * markers and all — meant for audit/debug. The replay path used by
 * think-engines goes through {@link #activeHistory}, which filters out
 * messages already rolled into a memory compaction so the LLM sees the
 * summary instead of the originals. The originals stay in Mongo, readable
 * via {@link #history}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatMessageService {

    /**
     * Primary key {@code createdAt} for chronological order, {@code id}
     * (the Mongo {@code ObjectId}) as tiebreaker — and as the only
     * reliable order for older documents inserted before Mongo
     * auditing was enabled. Their {@code createdAt} is {@code null}
     * so the primary key ranks them all together; the {@code ObjectId}
     * encodes the insert timestamp and breaks the tie monotonically.
     */
    private static final Sort BY_CREATED =
            Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id"));

    private final ChatMessageRepository repository;
    private final MongoTemplate mongoTemplate;
    private final SessionService sessionService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Persists {@code message}. The {@code createdAt} timestamp is filled in
     * by the framework on insert; callers do not need to set it.
     *
     * <p>After save, denormalises a short preview of the message onto
     * the owning {@code SessionDocument} so the inspector / session
     * picker can show "topic" + "what happened last" without reading
     * the full chat. See
     * {@link SessionService#touchChatPreview(String, String, String, java.time.Instant)}.
     */
    public ChatMessageDocument append(ChatMessageDocument message) {
        ChatMessageDocument saved = repository.save(message);
        log.debug("Chat message appended tenant='{}' session='{}' process='{}' role={} id='{}'",
                saved.getTenantId(), saved.getSessionId(), saved.getThinkProcessId(),
                saved.getRole(), saved.getId());
        sessionService.touchChatPreview(
                saved.getSessionId(),
                saved.getRole() == null ? null : saved.getRole().name(),
                saved.getContent(),
                saved.getCreatedAt());
        eventPublisher.publishEvent(new ChatMessageAppendedEvent(saved));
        return saved;
    }

    /**
     * Full chat history for a think-process, ordered by {@code createdAt}
     * ascending — including messages that have been archived into a
     * compaction memory. Use {@link #activeHistory} for the LLM-replay path.
     */
    public List<ChatMessageDocument> history(
            String tenantId, String sessionId, String thinkProcessId) {
        return repository.findByTenantIdAndSessionIdAndThinkProcessId(
                tenantId, sessionId, thinkProcessId, BY_CREATED);
    }

    /**
     * Active chat history — the messages that have <em>not</em> been
     * archived into a compaction memory. This is what an engine should
     * replay into the LLM context.
     */
    public List<ChatMessageDocument> activeHistory(
            String tenantId, String sessionId, String thinkProcessId) {
        return repository.findByTenantIdAndSessionIdAndThinkProcessIdAndArchivedInMemoryIdIsNull(
                tenantId, sessionId, thinkProcessId, BY_CREATED);
    }

    /**
     * Atomically marks a set of chat messages as archived in
     * {@code memoryId}. Idempotent — re-running with the same id is a
     * no-op for already-archived rows. Returns the number of rows
     * actually flipped.
     */
    public long markArchived(Collection<String> messageIds, String memoryId) {
        if (messageIds == null || messageIds.isEmpty()) return 0;
        Query query = new Query(Criteria.where("_id").in(messageIds)
                .and("archivedInMemoryId").isNull());
        Update update = new Update().set("archivedInMemoryId", memoryId);
        UpdateResult result = mongoTemplate.updateMulti(query, update, ChatMessageDocument.class);
        long n = result.getModifiedCount();
        if (n > 0) {
            log.debug("Archived {} chat message(s) into memory '{}'", n, memoryId);
        }
        return n;
    }

    /**
     * Atomically appends {@code newTags} to the message's tag set.
     * Idempotent: re-adding existing tags is a no-op (Mongo {@code $addToSet}).
     * A missing message id is silently ignored — Mongo {@code updateFirst}
     * matches zero rows in that case and returns without throwing.
     *
     * <p>Used by the tool-dispatcher hook to mark turns with markers like
     * {@code TOOL_CALL:*}, {@code RESOURCE:*}, {@code FILE_EDIT}; see
     * {@code planning/process-history-search.md} §5.
     */
    public void tag(String messageId, Set<String> newTags) {
        if (messageId == null || messageId.isBlank()) return;
        if (newTags == null || newTags.isEmpty()) return;
        Query q = new Query(Criteria.where("_id").is(messageId));
        Update u = new Update().addToSet("tags").each(newTags.toArray());
        mongoTemplate.updateFirst(q, u, ChatMessageDocument.class);
    }

    /**
     * Bulk variant of {@link #tag(String, Set)} — atomically adds a
     * single {@code tag} to every message in {@code messageIds}.
     * Idempotent on already-tagged messages ({@code $addToSet}).
     * Returns the number of rows actually modified.
     *
     * <p>Empty or {@code null} inputs are no-ops returning {@code 0}.
     */
    public long tagAll(java.util.Collection<String> messageIds, String tag) {
        if (messageIds == null || messageIds.isEmpty()) return 0;
        if (tag == null || tag.isBlank()) return 0;
        Query q = new Query(Criteria.where("_id").in(messageIds));
        Update u = new Update().addToSet("tags", tag);
        return mongoTemplate.updateMulti(q, u, ChatMessageDocument.class).getModifiedCount();
    }

    /**
     * Atomically removes every tag whose value starts with {@code prefix}
     * from the listed messages. Used together with {@link #tagAll} to
     * implement "replace single-value tag" semantics (e.g. swap the
     * current {@code STRENGTH:*} tag — there must be at most one per
     * message). Returns the number of rows modified.
     */
    public long removeTagsWithPrefix(
            java.util.Collection<String> messageIds, String prefix) {
        if (messageIds == null || messageIds.isEmpty()) return 0;
        if (prefix == null || prefix.isEmpty()) return 0;
        // Anchor with ^ so the prefix is a true left-anchor rather than
        // a contains-anywhere match.
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "^" + java.util.regex.Pattern.quote(prefix));
        Query q = new Query(Criteria.where("_id").in(messageIds));
        Update u = new Update().pull("tags", p);
        return mongoTemplate.updateMulti(q, u, ChatMessageDocument.class).getModifiedCount();
    }

    /**
     * Process-local search — equivalent to
     * {@link #search(ChatMessageSearchQuery, java.util.Set)} with the
     * scope set to just {@code q.thinkProcessId()}. Kept as the default
     * call-site so existing callers do not need to think about scope.
     */
    public List<ChatMessageDocument> search(ChatMessageSearchQuery q) {
        return search(q, java.util.Set.of(q.thinkProcessId()));
    }

    /**
     * Searches chat messages across an explicit set of allowed process
     * ids. {@code allowedProcessIds} is the resolved scope — for
     * {@code PROCESS} it is {@code {q.thinkProcessId()}}; for
     * {@code SESSION}/{@code CHILDREN} the caller (the tool layer)
     * pre-computes the set via {@code ThinkProcessService} so this
     * service stays scope-agnostic.
     *
     * <p>An empty {@code allowedProcessIds} returns an empty result —
     * never an unbounded search. Tenant pinning is unconditional and
     * applied before the {@code $in} filter.
     *
     * <p>{@code text} switches to a Mongo {@link TextQuery}; otherwise
     * a plain {@link Query} sorted by {@code createdAt} descending.
     * {@code tags} use {@code $all} (AND). {@code limit} is honoured
     * as-is (already clamped on the query).
     */
    public List<ChatMessageDocument> search(
            ChatMessageSearchQuery q, java.util.Set<String> allowedProcessIds) {
        if (allowedProcessIds == null || allowedProcessIds.isEmpty()) {
            return List.of();
        }
        Criteria c = Criteria.where("tenantId").is(q.tenantId())
                .and("thinkProcessId").in(allowedProcessIds);
        if (!q.tags().isEmpty()) {
            c = c.and("tags").all(q.tags());
        }
        if (q.since() != null) {
            c = c.and("createdAt").gte(q.since());
        }

        Query mongoQ;
        if (q.text() != null && !q.text().isBlank()) {
            TextCriteria tc = TextCriteria.forDefaultLanguage().matching(q.text());
            mongoQ = TextQuery.queryText(tc)
                    .sortByScore()
                    .addCriteria(c)
                    .with(Sort.by(Sort.Direction.DESC, "createdAt"))
                    .limit(q.limit());
        } else {
            mongoQ = new Query(c)
                    .with(Sort.by(Sort.Direction.DESC, "createdAt"))
                    .limit(q.limit());
        }
        return mongoTemplate.find(mongoQ, ChatMessageDocument.class);
    }

    /**
     * Process-local id lookup — equivalent to
     * {@link #findByIds(String, java.util.Set, java.util.Collection)}
     * with the scope set to just {@code thinkProcessId}.
     */
    public List<ChatMessageDocument> findByIds(
            String tenantId, String thinkProcessId, Collection<String> ids) {
        if (thinkProcessId == null || thinkProcessId.isBlank()) return List.of();
        return findByIds(tenantId, java.util.Set.of(thinkProcessId), ids);
    }

    /**
     * Looks up messages by id, strictly filtered to {@code tenantId} and
     * the {@code allowedProcessIds} set — the resolved scope from the
     * tool layer. Mongo {@code $in} on {@code thinkProcessId} keeps the
     * isolation explicit; an empty set yields an empty list, never an
     * unbounded fetch.
     *
     * <p>Order is {@code createdAt} ascending (chronological). Missing
     * ids are silently skipped: callers commonly pass ids returned by
     * an earlier {@code search} and should treat the response as a
     * filter, not as a strict resolve.
     */
    public List<ChatMessageDocument> findByIds(
            String tenantId,
            java.util.Set<String> allowedProcessIds,
            Collection<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        if (allowedProcessIds == null || allowedProcessIds.isEmpty()) {
            return List.of();
        }
        Query q = new Query(Criteria.where("_id").in(ids)
                .and("tenantId").is(tenantId)
                .and("thinkProcessId").in(allowedProcessIds))
                .with(Sort.by(Sort.Direction.ASC, "createdAt"));
        return mongoTemplate.find(q, ChatMessageDocument.class);
    }

    /**
     * Finds the most recent {@code createdAt} of a message carrying
     * {@code tag} within the given scope. Returns empty when no such
     * message exists — callers commonly treat that as "no boundary yet,
     * search from the start of the process".
     *
     * <p>Used by {@code list_edited_resources} to translate a
     * {@code sinceTag} parameter (e.g. {@code PLAN_STEP_STARTED:cleanup})
     * into a concrete time floor, so the LLM does not need to chain
     * {@code history_search} + {@code list_edited_resources} manually.
     */
    public Optional<Instant> findLatestCreatedAtForTag(
            String tenantId, Set<String> allowedProcessIds, String tag) {
        if (allowedProcessIds == null || allowedProcessIds.isEmpty()
                || tag == null || tag.isBlank()) {
            return Optional.empty();
        }
        Query q = new Query(Criteria.where("tenantId").is(tenantId)
                .and("thinkProcessId").in(allowedProcessIds)
                .and("tags").is(tag))
                .with(Sort.by(Sort.Direction.DESC, "createdAt"))
                .limit(1);
        ChatMessageDocument hit = mongoTemplate.findOne(q, ChatMessageDocument.class);
        return Optional.ofNullable(hit == null ? null : hit.getCreatedAt());
    }

    /**
     * Returns active (non-archived) chat messages of one think-process
     * whose {@code createdAt} falls inside the inclusive range
     * {@code [fromCreatedAtInclusive, toCreatedAtInclusive]}, ordered
     * chronologically.
     *
     * <p>Used by topic-recompaction to materialise the slice that should
     * be folded into a summary memory. Already-archived rows
     * ({@code archivedInMemoryId != null}) are skipped so the call is
     * idempotent: re-running compactRange over the same window touches
     * nothing on the second pass.
     *
     * <p>{@code null} bounds open the corresponding side; both
     * {@code null} reduces to "all active messages of this process". An
     * empty / blank {@code thinkProcessId} returns an empty list — never
     * an unbounded fetch.
     */
    public List<ChatMessageDocument> findActiveInRange(
            String tenantId,
            String thinkProcessId,
            @org.jspecify.annotations.Nullable Instant fromCreatedAtInclusive,
            @org.jspecify.annotations.Nullable Instant toCreatedAtInclusive) {
        if (thinkProcessId == null || thinkProcessId.isBlank()) {
            return List.of();
        }
        Criteria c = Criteria.where("tenantId").is(tenantId)
                .and("thinkProcessId").is(thinkProcessId)
                .and("archivedInMemoryId").isNull();
        if (fromCreatedAtInclusive != null && toCreatedAtInclusive != null) {
            c = c.and("createdAt").gte(fromCreatedAtInclusive).lte(toCreatedAtInclusive);
        } else if (fromCreatedAtInclusive != null) {
            c = c.and("createdAt").gte(fromCreatedAtInclusive);
        } else if (toCreatedAtInclusive != null) {
            c = c.and("createdAt").lte(toCreatedAtInclusive);
        }
        Query q = new Query(c).with(BY_CREATED);
        return mongoTemplate.find(q, ChatMessageDocument.class);
    }

    /**
     * Aggregates the distinct {@code RESOURCE:*} tag values seen within
     * the given scope, optionally floored at {@code since}. Each returned
     * string is a typed resource key — {@code CLIENT_FILE:/abs/path},
     * {@code WORKSPACE:<proc>/<rel>}, {@code DOCUMENT:<id>} — projected
     * straight from the tag (no {@code RESOURCE:} prefix in the response).
     *
     * <p>The implementation is a four-stage aggregation: match by
     * tenant + process + (optional) createdAt + tag prefix, unwind the
     * tags array, re-match the unwound rows to drop non-RESOURCE tags
     * that rode along on the same message, group on the tag value, sort
     * lexicographically.
     */
    public List<String> distinctResourceKeys(
            String tenantId, Set<String> allowedProcessIds, @org.jspecify.annotations.Nullable Instant since) {
        if (allowedProcessIds == null || allowedProcessIds.isEmpty()) {
            return List.of();
        }
        Criteria match = Criteria.where("tenantId").is(tenantId)
                .and("thinkProcessId").in(allowedProcessIds)
                .and("tags").regex("^RESOURCE:");
        if (since != null) {
            match = match.and("createdAt").gte(since);
        }
        Aggregation agg = Aggregation.newAggregation(
                Aggregation.match(match),
                Aggregation.unwind("tags"),
                Aggregation.match(Criteria.where("tags").regex("^RESOURCE:")),
                Aggregation.group("tags"),
                Aggregation.sort(Sort.Direction.ASC, "_id"));

        AggregationResults<org.bson.Document> results =
                mongoTemplate.aggregate(agg, ChatMessageDocument.class, org.bson.Document.class);

        List<String> out = new ArrayList<>();
        for (org.bson.Document row : results.getMappedResults()) {
            Object id = row.get("_id");
            if (id instanceof String s && s.startsWith("RESOURCE:")) {
                out.add(s.substring("RESOURCE:".length()));
            }
        }
        return out;
    }

    /** Drops all messages of a think-process (process deletion). */
    public long deleteByProcess(String tenantId, String sessionId, String thinkProcessId) {
        long n = repository.deleteByTenantIdAndSessionIdAndThinkProcessId(
                tenantId, sessionId, thinkProcessId);
        if (n > 0) {
            log.info("Deleted {} chat messages for process tenant='{}' session='{}' process='{}'",
                    n, tenantId, sessionId, thinkProcessId);
        }
        return n;
    }

    /** Drops all messages of a session (session close / cleanup). */
    public long deleteBySession(String tenantId, String sessionId) {
        long n = repository.deleteByTenantIdAndSessionId(tenantId, sessionId);
        if (n > 0) {
            log.info("Deleted {} chat messages for session tenant='{}' session='{}'",
                    n, tenantId, sessionId);
        }
        return n;
    }

    /**
     * Counts messages of a session with a given role. Used by
     * abandoned-session detection (§9.1) to determine whether a complete
     * Q&amp;A pair exists.
     */
    public long countBySessionAndRole(
            String tenantId, String sessionId, de.mhus.vance.api.chat.ChatRole role) {
        Query q = new Query(Criteria.where("tenantId").is(tenantId)
                .and("sessionId").is(sessionId)
                .and("role").is(role));
        return mongoTemplate.count(q, ChatMessageDocument.class);
    }

    /**
     * Returns the number of session messages carrying at least one tag
     * whose prefix matches one of {@code tagPrefixes}. Used by
     * abandoned-session detection to recognise tool-call activity
     * (e.g. {@code TOOL_CALL:*}, {@code FILE_EDIT}, {@code RESOURCE:*}).
     */
    public long countBySessionAndAnyTagPrefix(
            String tenantId, String sessionId, Collection<String> tagPrefixes) {
        if (tagPrefixes == null || tagPrefixes.isEmpty()) return 0;
        List<java.util.regex.Pattern> patterns = new ArrayList<>();
        for (String prefix : tagPrefixes) {
            if (prefix == null || prefix.isBlank()) continue;
            patterns.add(java.util.regex.Pattern.compile(
                    "^" + java.util.regex.Pattern.quote(prefix)));
        }
        if (patterns.isEmpty()) return 0;
        Query q = new Query(Criteria.where("tenantId").is(tenantId)
                .and("sessionId").is(sessionId)
                .and("tags").in(patterns));
        return mongoTemplate.count(q, ChatMessageDocument.class);
    }

    /**
     * Returns the first {@code limit} messages of a session in
     * chronological order, restricted to the given roles. Used by the
     * LLM auto-suggester to feed an opening-window summary into the
     * title/icon/color prompt.
     */
    public List<ChatMessageDocument> openingWindow(
            String tenantId, String sessionId,
            Collection<de.mhus.vance.api.chat.ChatRole> roles, int limit) {
        if (roles == null || roles.isEmpty() || limit <= 0) return List.of();
        Query q = new Query(Criteria.where("tenantId").is(tenantId)
                .and("sessionId").is(sessionId)
                .and("role").in(roles))
                .with(Sort.by(Sort.Direction.ASC, "createdAt"))
                .limit(limit);
        return mongoTemplate.find(q, ChatMessageDocument.class);
    }
}
