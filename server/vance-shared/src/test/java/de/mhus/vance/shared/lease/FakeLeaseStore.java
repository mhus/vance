package de.mhus.vance.shared.lease;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.bson.Document;
import org.jspecify.annotations.Nullable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Stateful in-memory stand-in for the two lease collections behind a
 * mocked {@link MongoTemplate}. It evaluates exactly the operations
 * {@link ResourceLeaseService} issues — {@code _id} filters, the
 * {@code count < cap} condition, {@code $inc}/{@code $set}/{@code $push}/
 * {@code $setOnInsert}/{@code $currentDate} — and reports matched vs.
 * modified like Mongo does (a touch that writes an identical value is
 * matched but not modified). The {@code $expr} idle filter is not
 * evaluated: tests declare expired holders via {@link #expire}.
 *
 * <p>Fault injection: {@link #failWhen} throws for the first matching
 * operation, so tests can break the funnel at a precise step.
 */
final class FakeLeaseStore {

    // Concurrent maps: a wait-budget test releases from a second thread.
    final Map<String, ResourceLeaseCounterDocument> counters = new ConcurrentHashMap<>();
    final Map<String, ResourceLeaseHolderDocument> holders = new ConcurrentHashMap<>();
    private final Set<String> expired = ConcurrentHashMap.newKeySet();
    private volatile @Nullable Predicate<String> failure;

    /** Fixed clock: two touches in a row write the same value (same-millisecond case). */
    private final Instant serverNow = Instant.parse("2026-10-14T12:00:00Z");

    final MongoTemplate template = mock(MongoTemplate.class);

    FakeLeaseStore() {
        when(template.findAndModify(any(Query.class), any(Update.class), eq(ResourceLeaseCounterDocument.class)))
                .thenAnswer(inv -> incCounter(inv.getArgument(0), inv.getArgument(1)));
        when(template.upsert(any(Query.class), any(Update.class), eq(ResourceLeaseCounterDocument.class)))
                .thenAnswer(inv -> ensureCounter(inv.getArgument(0), inv.getArgument(1)));
        when(template.updateFirst(any(Query.class), any(Update.class), eq(ResourceLeaseCounterDocument.class)))
                .thenAnswer(inv -> updateCounter(inv.getArgument(0), inv.getArgument(1)));
        when(template.remove(any(Query.class), eq(ResourceLeaseCounterDocument.class)))
                .thenAnswer(inv -> removeCounter(inv.getArgument(0)));
        when(template.findById(any(), eq(ResourceLeaseCounterDocument.class)))
                .thenAnswer(inv -> counters.get(String.valueOf(inv.getArgument(0, Object.class))));
        when(template.findAll(ResourceLeaseCounterDocument.class))
                .thenAnswer(inv -> new ArrayList<>(counters.values()));

        when(template.upsert(any(Query.class), any(Update.class), eq(ResourceLeaseHolderDocument.class)))
                .thenAnswer(inv -> upsertHolder(inv.getArgument(0), inv.getArgument(1)));
        when(template.updateFirst(any(Query.class), any(Update.class), eq(ResourceLeaseHolderDocument.class)))
                .thenAnswer(inv -> updateHolder(inv.getArgument(0), inv.getArgument(1)));
        when(template.findAndRemove(any(Query.class), eq(ResourceLeaseHolderDocument.class)))
                .thenAnswer(inv -> {
                    fail("holder.remove");
                    Query query = inv.getArgument(0);
                    ResourceLeaseHolderDocument holder = holders.get(id(query));
                    if (holder == null || !matchesFields(holder, query.getQueryObject())) return null;
                    return holders.remove(holder.getToken());
                });
        when(template.find(any(Query.class), eq(ResourceLeaseHolderDocument.class)))
                .thenAnswer(inv -> {
                    Document filter = ((Query) inv.getArgument(0)).getQueryObject();
                    return holders.values().stream()
                            .filter(h -> !filter.containsKey("$expr") || expired.contains(h.getToken()))
                            .filter(h -> matchesFields(h, filter))
                            .toList();
                });
        when(template.findById(any(), eq(ResourceLeaseHolderDocument.class)))
                .thenAnswer(inv -> holders.get(String.valueOf(inv.getArgument(0, Object.class))));
        when(template.findAll(ResourceLeaseHolderDocument.class)).thenAnswer(inv -> new ArrayList<>(holders.values()));
    }

    /** Throw on the first operation whose label matches (e.g. {@code "holder.push"}). */
    void failWhen(Predicate<String> operation) {
        this.failure = operation;
    }

    void expire(String token) {
        expired.add(token);
    }

    long count(String key) {
        ResourceLeaseCounterDocument c = counters.get(key);
        return c == null ? 0 : c.getCount();
    }

    void putCounter(String key, long count, Instant updatedAt) {
        counters.put(key, new ResourceLeaseCounterDocument(key, count, updatedAt));
    }

    private void fail(String operation) {
        Predicate<String> f = failure;
        if (f != null && f.test(operation)) {
            failure = null;
            throw new IllegalStateException("injected store failure at " + operation);
        }
    }

    // ──────────────── counters ────────────────

    private @Nullable ResourceLeaseCounterDocument incCounter(Query query, Update update) {
        fail("counter.inc");
        Document filter = query.getQueryObject();
        ResourceLeaseCounterDocument counter = counters.get(String.valueOf(filter.get("_id")));
        if (counter == null) return null;
        Object cond = filter.get("count");
        if (cond instanceof Document lt && counter.getCount() >= ((Number) lt.get("$lt")).longValue()) {
            return null;
        }
        apply(counter, update);
        return counter;
    }

    private UpdateResult ensureCounter(Query query, Update update) {
        String key = id(query);
        if (counters.containsKey(key)) return UpdateResult.acknowledged(1, 0L, null);
        Document onInsert = (Document) update.getUpdateObject().get("$setOnInsert");
        counters.put(key, new ResourceLeaseCounterDocument(key, ((Number) onInsert.get("count")).longValue(), (Instant)
                onInsert.get("updatedAt")));
        return UpdateResult.acknowledged(0, 0L, null);
    }

    private UpdateResult updateCounter(Query query, Update update) {
        ResourceLeaseCounterDocument counter = matchingCounter(query);
        if (counter == null) return UpdateResult.acknowledged(0, 0L, null);
        apply(counter, update);
        return UpdateResult.acknowledged(1, 1L, null);
    }

    private DeleteResult removeCounter(Query query) {
        ResourceLeaseCounterDocument counter = matchingCounter(query);
        if (counter == null) return DeleteResult.acknowledged(0);
        counters.remove(counter.getId());
        return DeleteResult.acknowledged(1);
    }

    /** {@code _id} plus optional {@code count}/{@code updatedAt} equality — the reconcile filter. */
    private @Nullable ResourceLeaseCounterDocument matchingCounter(Query query) {
        Document filter = query.getQueryObject();
        ResourceLeaseCounterDocument counter = counters.get(String.valueOf(filter.get("_id")));
        if (counter == null) return null;
        if (filter.containsKey("count") && ((Number) filter.get("count")).longValue() != counter.getCount()) {
            return null;
        }
        if (filter.containsKey("updatedAt") && !Objects.equals(filter.get("updatedAt"), counter.getUpdatedAt())) {
            return null;
        }
        return counter;
    }

    private void apply(ResourceLeaseCounterDocument counter, Update update) {
        Document ops = update.getUpdateObject();
        if (ops.get("$inc") instanceof Document inc) {
            counter.setCount(counter.getCount() + ((Number) inc.get("count")).longValue());
        }
        if (ops.get("$set") instanceof Document set && set.containsKey("count")) {
            counter.setCount(((Number) set.get("count")).longValue());
        }
        if (ops.containsKey("$currentDate")) {
            counter.setUpdatedAt(serverNow);
        }
    }

    // ──────────────── holders ────────────────

    @SuppressWarnings("unchecked")
    private UpdateResult upsertHolder(Query query, Update update) {
        fail("holder.insert");
        Document set = (Document) update.getUpdateObject().get("$set");
        ResourceLeaseHolderDocument holder = ResourceLeaseHolderDocument.builder()
                .token(id(query))
                .state((String) set.get("state"))
                .type((String) set.get("type"))
                .resource((String) set.get("resource"))
                .holderRef((String) set.get("holderRef"))
                .tenantId((String) set.get("tenantId"))
                .projectId((String) set.get("projectId"))
                .userId((String) set.get("userId"))
                .pod((String) set.get("pod"))
                .podBound((Boolean) set.get("podBound"))
                .payload((Map<String, Object>) set.get("payload"))
                .levels(new ArrayList<>((List<String>) set.get("levels")))
                .ttlSeconds(((Number) set.get("ttlSeconds")).longValue())
                .acquiredAt(serverNow)
                .lastSeenAt(serverNow)
                .build();
        holders.put(holder.getToken(), holder);
        return UpdateResult.acknowledged(0, 0L, null);
    }

    @SuppressWarnings("unchecked")
    private UpdateResult updateHolder(Query query, Update update) {
        Document ops = update.getUpdateObject();
        fail(ops.containsKey("$push") ? "holder.push" : "holder.update");
        ResourceLeaseHolderDocument holder = holders.get(id(query));
        if (holder == null) return UpdateResult.acknowledged(0, 0L, null);
        boolean modified = false;
        if (ops.get("$push") instanceof Document push) {
            holder.getLevels().add((String) push.get("levels"));
            modified = true;
        }
        if (ops.get("$set") instanceof Document set) {
            if (set.containsKey("state") && !Objects.equals(set.get("state"), holder.getState())) {
                holder.setState((String) set.get("state"));
                modified = true;
            }
            if (set.containsKey("payload") && !Objects.equals(set.get("payload"), holder.getPayload())) {
                holder.setPayload((Map<String, Object>) set.get("payload"));
                modified = true;
            }
        }
        if (ops.containsKey("$currentDate") && !serverNow.equals(holder.getLastSeenAt())) {
            holder.setLastSeenAt(serverNow);
            modified = true;
        }
        return UpdateResult.acknowledged(1, modified ? 1L : 0L, null);
    }

    /**
     * Plain equality on the holder fields a filter names ({@code tenantId},
     * {@code pod}, {@code resource}) — the {@code _id} and the {@code $expr}
     * idle filter are handled by the callers.
     */
    private static boolean matchesFields(ResourceLeaseHolderDocument holder, Document filter) {
        return equalIfPresent(filter, "tenantId", holder.getTenantId())
                && equalIfPresent(filter, "pod", holder.getPod())
                && equalIfPresent(filter, "resource", holder.getResource());
    }

    private static boolean equalIfPresent(Document filter, String field, @Nullable Object actual) {
        return !filter.containsKey(field) || Objects.equals(filter.get(field), actual);
    }

    private static String id(Query query) {
        return String.valueOf(query.getQueryObject().get("_id"));
    }
}
