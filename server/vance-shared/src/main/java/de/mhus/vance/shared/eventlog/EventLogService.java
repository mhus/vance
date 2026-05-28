package de.mhus.vance.shared.eventlog;

import de.mhus.vance.api.eventlog.EventType;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Append, query, and prune entries in the generic event log. The log is
 * a single Mongo collection shared by all trigger sources — schedulers
 * today, webhooks and hooks later. See
 * {@code specification/scheduler.md} §7.
 *
 * <p>Reads are pre-sorted by {@code timestamp} descending so the most
 * recent activity is the first row in every query — that's how UI list
 * views and the {@link #findLatest} lookup expect the data to arrive.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EventLogService {

    private final EventLogRepository repository;
    private final MongoTemplate mongoTemplate;

    /**
     * Append a new entry. {@code timestamp} is set to {@link Instant#now()}
     * if the builder hasn't filled it. Producers should always pass a
     * {@code correlationId} so the events of one run can be joined later;
     * the only events that may legitimately stand alone are external-state
     * notifications added by future producers.
     */
    public EventLogDocument append(EventLogDocument toCreate) {
        if (toCreate.getTimestamp() == null || toCreate.getTimestamp() == Instant.EPOCH) {
            toCreate.setTimestamp(Instant.now());
        }
        if (toCreate.getPayload() == null) {
            toCreate.setPayload(new LinkedHashMap<>());
        }
        EventLogDocument saved = repository.save(toCreate);
        log.debug("EventLog append source='{}' type={} correlation='{}'",
                saved.getSource(), saved.getType(), saved.getCorrelationId());
        return saved;
    }

    /**
     * Latest event for a given {@code source}, optionally filtered to a
     * subset of types. Used by scheduler overlap-checks ("is the previous
     * run still active?") and by UI list rendering ("when did this last run?").
     *
     * @param types optional filter — pass {@code null} or empty for "any type"
     */
    public Optional<EventLogDocument> findLatest(
            String tenantId,
            String source,
            @Nullable List<EventType> types) {
        Criteria c = Criteria.where("tenantId").is(tenantId)
                .and("source").is(source);
        if (types != null && !types.isEmpty()) {
            c = c.and("type").in(types);
        }
        Query q = new Query(c).with(Sort.by(Sort.Order.desc("timestamp"))).limit(1);
        return Optional.ofNullable(mongoTemplate.findOne(q, EventLogDocument.class));
    }

    /**
     * Recent entries for a {@code source}, newest first. {@code limit}
     * clamped to {@code [1, 500]}.
     */
    public List<EventLogDocument> listBySource(String tenantId, String source, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        Query q = new Query(Criteria.where("tenantId").is(tenantId)
                        .and("source").is(source))
                .with(PageRequest.of(0, safeLimit, Sort.by(Sort.Order.desc("timestamp"))));
        return mongoTemplate.find(q, EventLogDocument.class);
    }

    /**
     * Recent entries scoped to a project — used by the project-level event
     * stream view in the Web-UI.
     */
    public List<EventLogDocument> listByProject(
            String tenantId, @Nullable String projectId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        Criteria c = Criteria.where("tenantId").is(tenantId);
        if (projectId != null && !projectId.isBlank()) {
            c = c.and("projectId").is(projectId);
        }
        Query q = new Query(c).with(
                PageRequest.of(0, safeLimit, Sort.by(Sort.Order.desc("timestamp"))));
        return mongoTemplate.find(q, EventLogDocument.class);
    }

    /**
     * Most recent {@code STARTED} event for a given Mongo process id —
     * used by the process-lifecycle listener to look up the trigger
     * source and {@code correlationId} when emitting the matching
     * terminal event. Returns empty if the process was not spawned via
     * a logged trigger (e.g. user-driven, recipe-spawned worker).
     *
     * @param sourcePrefix optional filter "any source that starts with
     *                     this prefix" — pass e.g. {@code "ursascheduler:"}
     *                     to scope to scheduler-spawned processes.
     */
    public Optional<EventLogDocument> findStartForProcess(
            String tenantId, String processId, @Nullable String sourcePrefix) {
        Criteria c = Criteria.where("tenantId").is(tenantId)
                .and("processId").is(processId)
                .and("type").is(EventType.STARTED);
        if (sourcePrefix != null && !sourcePrefix.isBlank()) {
            c = c.and("source").regex("^" + java.util.regex.Pattern.quote(sourcePrefix));
        }
        Query q = new Query(c).with(Sort.by(Sort.Order.desc("timestamp"))).limit(1);
        return Optional.ofNullable(mongoTemplate.findOne(q, EventLogDocument.class));
    }

    /** All events that share a {@code correlationId} — the run-detail view. */
    public List<EventLogDocument> listByCorrelation(String correlationId) {
        List<EventLogDocument> rows = repository.findByCorrelationId(correlationId);
        rows.sort((a, b) -> {
            Instant ta = a.getTimestamp() == null ? Instant.EPOCH : a.getTimestamp();
            Instant tb = b.getTimestamp() == null ? Instant.EPOCH : b.getTimestamp();
            return ta.compareTo(tb);
        });
        return rows;
    }

    /**
     * Hard-delete every entry older than {@code cutoff}, optionally scoped
     * to a single source. Used by the daily TTL cleanup; not part of any
     * synchronous path.
     *
     * @return number of rows actually removed
     */
    public long deleteOlderThan(Instant cutoff, @Nullable String sourceFilter) {
        Criteria c = Criteria.where("timestamp").lt(cutoff);
        if (sourceFilter != null && !sourceFilter.isBlank()) {
            c = c.and("source").is(sourceFilter);
        }
        long removed = mongoTemplate.remove(new Query(c), EventLogDocument.class)
                .getDeletedCount();
        if (removed > 0) {
            log.info("EventLog pruned {} entries older than {} sourceFilter={}",
                    removed, cutoff, sourceFilter);
        }
        return removed;
    }

    /**
     * Helper for the common path "append in one call". Lets the caller skip
     * the builder boilerplate for the throw-away {@link EventLogDocument}.
     */
    public EventLogDocument append(
            String tenantId,
            @Nullable String projectId,
            String source,
            EventType type,
            @Nullable String correlationId,
            @Nullable String sessionId,
            @Nullable String processId,
            @Nullable String runAs,
            @Nullable Map<String, Object> payload) {
        return append(EventLogDocument.builder()
                .tenantId(tenantId)
                .projectId(projectId)
                .source(source)
                .type(type)
                .correlationId(correlationId)
                .sessionId(sessionId)
                .processId(processId)
                .runAs(runAs)
                .payload(payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload))
                .build());
    }
}
