package de.mhus.vance.shared.lease;

import com.mongodb.client.result.UpdateResult;
import de.mhus.vance.shared.metric.MetricService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Core of the resource-lease system: acquisition through the quota funnel,
 * check-at-use touch, release, idle/pod reaping and counter reconcile.
 * All state lives in Mongo ({@code resource_lease_counters} /
 * {@code resource_lease_holders}) so every pod sees the same funnel —
 * {@code planning/lease-system.md} §5 remains the reference for the
 * write ordering and §6 for the TTL semantics.
 *
 * <p>Acquisition checks the levels <b>narrow → wide</b>
 * ({@code user → project → tenant → global}): a rejected request usually
 * fails on a fairness level and never touches the hot global counter
 * document. Fixed order + one resource per acquisition = no deadlock
 * question.
 *
 * <p>Fail-closed: a Mongo error during {@link #acquire} surfaces as
 * {@link LeaseUnavailableException} — the resource is not used
 * unprotected. A known handler type is required at acquire (the reaper
 * must be able to dispatch a revocation later); handler beans are
 * optional because this service also runs in contexts without any
 * (anus).
 */
@Service
@Slf4j
public class ResourceLeaseService {

    static final String STATE_PENDING = "PENDING";
    static final String STATE_HELD = "HELD";

    private static final String SEPARATOR = "|";
    private static final String LEVEL_GLOBAL = "global";
    private static final String LEVEL_TENANT = "tenant";
    private static final String LEVEL_PROJECT = "project";
    private static final String LEVEL_USER = "user";

    private final MongoTemplate mongoTemplate;
    private final LeaseDefinitionLoader definitionLoader;
    private final MetricService metrics;
    private final ObjectProvider<LeaseRevocationListener> revocationListeners;
    private final Set<String> knownTypes;

    public ResourceLeaseService(
            MongoTemplate mongoTemplate,
            LeaseDefinitionLoader definitionLoader,
            MetricService metrics,
            ObjectProvider<LeaseHandler> handlers,
            ObjectProvider<LeaseRevocationListener> revocationListeners) {
        this.mongoTemplate = mongoTemplate;
        this.definitionLoader = definitionLoader;
        this.metrics = metrics;
        this.revocationListeners = revocationListeners;
        Set<String> types = new HashSet<>();
        handlers.orderedStream().forEach(h -> types.add(h.type()));
        this.knownTypes = Set.copyOf(types);
    }

    // ──────────────── acquire ────────────────

    /**
     * Acquires a lease as described by the request. Waits up to the
     * definition's {@code waitMs} (poll with jitter — waiting pods must
     * not stampede the counter in lockstep), then throws
     * {@link LeaseBusyException} with a diagnosis. A definition without
     * any cap yields {@link Lease#NOOP} without Mongo traffic.
     */
    public Lease acquire(LeaseRequest request) {
        if (!knownTypes.contains(request.type())) {
            throw new LeaseUnavailableException("Unknown lease type '" + request.type()
                    + "' — no LeaseHandler bean registered. " + "Known types: " + knownTypes);
        }
        LeaseDefinition def = resolveDefinition(request);
        if (!def.hasAnyCap()) {
            metrics.counter("vance.lease.acquire", "resource", def.resourceKey(), "outcome", "noop")
                    .increment();
            return Lease.NOOP;
        }

        List<Level> levels = funnel(def, request);
        long deadline = System.currentTimeMillis() + def.waitMsOrDefault();
        try {
            // Opportunistic idle reap scoped to this resource — frees slots
            // of crashed holders before we decide we're busy. The reaped
            // holders still need their handler (§5), hence the listener.
            notifyReaped(reapIdle(def.resourceKey()));
            while (true) {
                try {
                    Lease lease = tryAcquire(request, def, levels);
                    metrics.counter("vance.lease.acquire", "resource", def.resourceKey(), "outcome", "success")
                            .increment();
                    return lease;
                } catch (BusySignal busy) {
                    if (System.currentTimeMillis() >= deadline) {
                        metrics.counter("vance.lease.acquire", "resource", def.resourceKey(), "outcome", "busy")
                                .increment();
                        throw new LeaseBusyException(diagnose(def, busy));
                    }
                    sleepWithJitter(deadline);
                }
            }
        } catch (LeaseBusyException e) {
            throw e;
        } catch (RuntimeException e) {
            metrics.counter("vance.lease.acquire", "resource", def.resourceKey(), "outcome", "unavailable")
                    .increment();
            throw (e instanceof LeaseUnavailableException lun)
                    ? lun
                    : new LeaseUnavailableException("lease store unavailable: " + e, e);
        }
    }

    /** Hands holders reaped inside this service to the listener; never fails the caller. */
    private void notifyReaped(List<ResourceLeaseHolderDocument> reaped) {
        if (reaped.isEmpty()) return;
        LeaseRevocationListener listener = revocationListeners.getIfAvailable();
        if (listener == null) {
            log.warn(
                    "resource lease: {} holder(s) reaped without a revocation listener — handlers not run",
                    reaped.size());
            return;
        }
        try {
            listener.onReaped(reaped.stream().map(LeaseHolderInfo::of).toList());
        } catch (RuntimeException e) {
            log.warn("resource lease: revocation listener failed: {}", e.toString());
        }
    }

    private LeaseDefinition resolveDefinition(LeaseRequest request) {
        if (request.inline() != null) {
            return request.inline();
        }
        String name = request.leaseName() == null ? "" : request.leaseName().trim();
        if (name.isEmpty()) {
            throw new LeaseUnavailableException("LeaseRequest without a lease name — reference a lease config by name");
        }
        if (!LeaseDefinition.isValidName(name)) {
            throw new LeaseUnavailableException(
                    "invalid lease name '" + name + "' — allowed: " + LeaseDefinition.NAME_PATTERN.pattern());
        }
        return definitionLoader
                .load(request.tenantId(), request.projectId(), name)
                .orElseThrow(() -> new LeaseUnavailableException(
                        "lease '" + name + "' is not defined in any layer (classpath, system, tenant, project)"));
    }

    private record Level(String key, String label, long cap) {}

    /** The funnel levels this request has to pass, narrow → wide. */
    private List<Level> funnel(LeaseDefinition def, LeaseRequest request) {
        String resource = def.resourceKey();
        List<Level> levels = new ArrayList<>(4);
        if (def.maxUser() != null && request.userId() != null) {
            levels.add(new Level(
                    // Per user across the tenant's projects — "one active
                    // browser session per user" must not multiply by project.
                    counterKey(resource, LEVEL_USER, request.tenantId() + "/" + request.userId()),
                    "user",
                    def.maxUser()));
        }
        if (def.maxProject() != null && request.projectId() != null) {
            levels.add(new Level(
                    counterKey(resource, LEVEL_PROJECT, request.tenantId() + "/" + request.projectId()),
                    "project",
                    def.maxProject()));
        }
        if (def.maxTenant() != null) {
            levels.add(new Level(counterKey(resource, LEVEL_TENANT, request.tenantId()), "tenant", def.maxTenant()));
        }
        if (def.maxGlobal() != null) {
            levels.add(new Level(counterKey(resource, LEVEL_GLOBAL, ""), "global", def.maxGlobal()));
        }
        return levels;
    }

    private Lease tryAcquire(LeaseRequest request, LeaseDefinition def, List<Level> levels) {
        String token = "rl-" + UUID.randomUUID();

        // Holder first (§5): a raised counter never exists without a holder
        // document that knows about it — reconcile recomputes from holders.
        // Written as an upsert so acquiredAt/lastSeenAt come from the Mongo
        // server clock like every later touch — a pod clock behind by more
        // than the TTL would otherwise get its PENDING holder reaped.
        mongoTemplate.upsert(
                byToken(token),
                new Update()
                        .set("state", STATE_PENDING)
                        .set("type", request.type())
                        .set("resource", def.resourceKey())
                        .set("holderRef", request.holderRef())
                        .set("tenantId", request.tenantId())
                        .set("projectId", request.projectId())
                        .set("userId", request.userId())
                        .set("pod", request.pod())
                        .set("podBound", request.podBound())
                        .set("payload", request.payload())
                        .set("levels", new ArrayList<String>())
                        .set("ttlSeconds", def.ttlSecondsOrDefault())
                        .currentDate("acquiredAt")
                        .currentDate("lastSeenAt"),
                ResourceLeaseHolderDocument.class);

        List<String> acquired = new ArrayList<>(levels.size());
        try {
            for (Level level : levels) {
                if (!incCounter(level.key(), level.cap())) {
                    throw new BusySignal(level);
                }
                acquired.add(level.key());
                mongoTemplate.updateFirst(
                        byToken(token), new Update().push("levels", level.key()), ResourceLeaseHolderDocument.class);
            }
            UpdateResult held = mongoTemplate.updateFirst(
                    byToken(token),
                    new Update().set("state", STATE_HELD).currentDate("lastSeenAt"),
                    ResourceLeaseHolderDocument.class);
            if (held.getMatchedCount() == 0) {
                // A reaper took the PENDING holder (and its booked levels)
                // under our hands — nothing was handed out yet.
                throw new LeaseUnavailableException("lease holder " + token + " was reaped during acquisition");
            }
        } catch (BusySignal busy) {
            // Rollback: give back everything this attempt took — the outer
            // wait loop starts a fresh attempt.
            rollback(token, acquired);
            throw busy;
        } catch (RuntimeException e) {
            // Store failure mid-funnel: the same rollback, best effort — a
            // half-booked holder would block a cap-1 slot for a whole TTL.
            try {
                rollback(token, acquired);
            } catch (RuntimeException rollbackFailure) {
                log.warn(
                        "lease {}: rollback after failed acquisition failed (reconcile/idle reap clean up): {}",
                        token,
                        rollbackFailure.toString());
            }
            throw e;
        }
        return new HeldLease(token, def.resourceKey());
    }

    /**
     * Undoes a failed attempt. Only the remover of the holder document
     * books counters back: when a reaper got there first it already
     * decremented the levels the document recorded — anything incremented
     * but not yet recorded is left to reconcile.
     */
    private void rollback(String token, List<String> acquired) {
        ResourceLeaseHolderDocument removed =
                mongoTemplate.findAndRemove(byToken(token), ResourceLeaseHolderDocument.class);
        if (removed == null) return;
        for (String key : acquired) {
            decCounter(key);
        }
    }

    /** Internal busy marker — carries the level that rejected us. */
    private static final class BusySignal extends RuntimeException {
        private final Level level;

        BusySignal(Level level) {
            super(null, null, false, false);
            this.level = level;
        }
    }

    // ──────────────── lease operations (via the returned Lease) ────────────────

    /** Usage step: verify + touch (§6). One roundtrip on Mongo server time. */
    void check(String token, String resource) {
        try {
            var result = mongoTemplate.updateFirst(
                    Query.query(Criteria.where("_id").is(token)),
                    new Update().currentDate("lastSeenAt"),
                    ResourceLeaseHolderDocument.class);
            // Matched, not modified: two touches within the same millisecond
            // write an identical document and Mongo reports nModified = 0.
            if (result.getMatchedCount() == 0) {
                throw new LeaseLostException(
                        "lease " + token + " on '" + resource + "' was revoked (idle or pod reaped)");
            }
        } catch (LeaseLostException e) {
            metrics.counter("vance.lease.check", "resource", resource, "outcome", "lost")
                    .increment();
            throw e;
        } catch (RuntimeException e) {
            throw new LeaseUnavailableException("lease store unavailable during check: " + e, e);
        }
        metrics.counter("vance.lease.check", "resource", resource, "outcome", "ok")
                .increment();
    }

    void updatePayload(String token, String resource, Map<String, Object> payload) {
        try {
            var result = mongoTemplate.updateFirst(
                    Query.query(Criteria.where("_id").is(token)),
                    new Update().set("payload", Map.copyOf(payload)).currentDate("lastSeenAt"),
                    ResourceLeaseHolderDocument.class);
            if (result.getMatchedCount() == 0) {
                throw new LeaseLostException(
                        "lease " + token + " on '" + resource + "' was revoked — payload update rejected");
            }
        } catch (LeaseLostException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new LeaseUnavailableException("lease store unavailable during payload update: " + e, e);
        }
    }

    /** Frees the slot. Idempotent — releasing twice is a no-op. */
    void release(String token, String resource) {
        ResourceLeaseHolderDocument holder;
        try {
            holder = mongoTemplate.findAndRemove(
                    Query.query(Criteria.where("_id").is(token)), ResourceLeaseHolderDocument.class);
        } catch (RuntimeException e) {
            log.warn("lease {}: release failed (counters may reconcile later): {}", token, e.toString());
            metrics.counter("vance.lease.release", "resource", resource, "outcome", "error")
                    .increment();
            return;
        }
        if (holder == null) {
            metrics.counter("vance.lease.release", "resource", resource, "outcome", "already-released")
                    .increment();
            return;
        }
        for (String key : holder.getLevels()) {
            decCounter(key);
        }
        metrics.counter("vance.lease.release", "resource", resource, "outcome", "ok")
                .increment();
    }

    // ──────────────── reaping & reconcile ────────────────

    /**
     * Deletes holders idle past their TTL (server-time comparison) and
     * returns them for handler dispatch. Callers that only want room on one
     * resource pass it; {@code null} sweeps everything.
     */
    public List<ResourceLeaseHolderDocument> reapIdle(@Nullable String resource) {
        Document youngerThan = new Document(
                "$lt",
                List.of(
                        "$lastSeenAt",
                        new Document(
                                "$subtract",
                                List.of("$$NOW", new Document("$multiply", List.of("$ttlSeconds", 1000L))))));
        Query query = new Query(new Criteria("$expr").is(youngerThan));
        if (resource != null) {
            query.addCriteria(Criteria.where("resource").is(resource));
        }
        List<ResourceLeaseHolderDocument> reaped = new ArrayList<>();
        for (ResourceLeaseHolderDocument expired : mongoTemplate.find(query, ResourceLeaseHolderDocument.class)) {
            ResourceLeaseHolderDocument removed = mongoTemplate.findAndRemove(
                    Query.query(Criteria.where("_id").is(expired.getToken())), ResourceLeaseHolderDocument.class);
            if (removed != null) {
                removed.getLevels().forEach(this::decCounter);
                reaped.add(removed);
            }
        }
        if (!reaped.isEmpty()) {
            metrics.counter("vance.lease.reap", "reason", "idle").increment(reaped.size());
            log.debug(
                    "resource lease: idle-reaped {} holder(s){}",
                    reaped.size(),
                    resource == null ? "" : " on '" + resource + "'");
        }
        return reaped;
    }

    /** Deletes every holder of one pod (dead-pod reap) and returns them. */
    public List<ResourceLeaseHolderDocument> reapPod(String podId) {
        List<ResourceLeaseHolderDocument> reaped = new ArrayList<>();
        for (ResourceLeaseHolderDocument holder :
                mongoTemplate.find(Query.query(Criteria.where("pod").is(podId)), ResourceLeaseHolderDocument.class)) {
            ResourceLeaseHolderDocument removed = mongoTemplate.findAndRemove(
                    Query.query(Criteria.where("_id").is(holder.getToken())), ResourceLeaseHolderDocument.class);
            if (removed != null) {
                removed.getLevels().forEach(this::decCounter);
                reaped.add(removed);
            }
        }
        if (!reaped.isEmpty()) {
            metrics.counter("vance.lease.reap", "reason", "pod").increment(reaped.size());
            log.info("resource lease: pod-reaped {} holder(s) of pod '{}'", reaped.size(), podId);
        }
        return reaped;
    }

    /**
     * Recomputes the counters from the holder documents and corrects drift
     * (crash windows between counter increment and level push, between
     * holder delete and counter decrement). Only counters quiet for the
     * safety window are corrected — a running acquisition on another pod
     * must not be "fixed" under its hands.
     *
     * @return number of corrected counters
     */
    public long reconcile(Instant safetyNotBefore) {
        Map<String, Long> actual = new HashMap<>();
        for (ResourceLeaseHolderDocument holder : mongoTemplate.findAll(ResourceLeaseHolderDocument.class)) {
            for (String key : holder.getLevels()) {
                actual.merge(key, 1L, Long::sum);
            }
        }
        long corrections = 0;
        long removed = 0;
        for (ResourceLeaseCounterDocument counter : mongoTemplate.findAll(ResourceLeaseCounterDocument.class)) {
            if (counter.getUpdatedAt() == null || counter.getUpdatedAt().isAfter(safetyNotBefore)) {
                continue;
            }
            long real = actual.getOrDefault(counter.getId(), 0L);
            // The filter pins the observed state: any write since the read
            // (count or updatedAt) makes the correction a no-op.
            Query unchanged = Query.query(Criteria.where("_id")
                    .is(counter.getId())
                    .and("count")
                    .is(counter.getCount())
                    .and("updatedAt")
                    .is(counter.getUpdatedAt()));
            if (real == 0) {
                // No holder books this level: drop the counter instead of
                // keeping one quiet document per user/project forever. The
                // next acquisition re-creates it.
                if (mongoTemplate
                                .remove(unchanged, ResourceLeaseCounterDocument.class)
                                .getDeletedCount()
                        > 0) {
                    removed++;
                    if (counter.getCount() != 0) {
                        corrections++;
                        log.warn(
                                "resource lease: reconcile corrected counter '{}' {} → 0 (removed)",
                                counter.getId(),
                                counter.getCount());
                    }
                }
            } else if (real != counter.getCount()) {
                if (mongoTemplate
                                .updateFirst(
                                        unchanged, new Update().set("count", real), ResourceLeaseCounterDocument.class)
                                .getMatchedCount()
                        > 0) {
                    corrections++;
                    log.warn(
                            "resource lease: reconcile corrected counter '{}' {} → {}",
                            counter.getId(),
                            counter.getCount(),
                            real);
                }
            }
        }
        if (corrections > 0) {
            metrics.counter("vance.lease.reconcile", "outcome", "corrected").increment(corrections);
        }
        if (removed > 0) {
            log.debug("resource lease: reconcile removed {} idle counter(s)", removed);
        }
        return corrections;
    }

    // ──────────────── operator surface ────────────────

    /**
     * Revokes one holder on an operator's decision. Same effect as a reap —
     * the holder document goes, its booked levels are given back — and the
     * removed holder is returned so the caller can run the type's handler
     * (§5: a freed slot alone leaves the resource running). The holder
     * itself learns it at its next {@link Lease#check()}.
     *
     * @param tenantId when set, only a holder of this tenant is revoked —
     *     tokens are global, the tenant admin's surface must not reach past
     *     its own tenant
     * @return the removed holder, empty when the token is unknown, already
     *     gone or belongs to another tenant
     */
    public Optional<ResourceLeaseHolderDocument> revoke(String token, @Nullable String tenantId) {
        Criteria criteria = Criteria.where("_id").is(token);
        if (tenantId != null) {
            criteria = criteria.and("tenantId").is(tenantId);
        }
        ResourceLeaseHolderDocument removed =
                mongoTemplate.findAndRemove(Query.query(criteria), ResourceLeaseHolderDocument.class);
        if (removed == null) return Optional.empty();
        removed.getLevels().forEach(this::decCounter);
        metrics.counter("vance.lease.reap", "reason", "revoke").increment();
        log.info(
                "resource lease: revoked holder {} on '{}' (type {}, pod '{}')",
                removed.getToken(),
                removed.getResource(),
                removed.getType(),
                removed.getPod());
        return Optional.of(removed);
    }

    /** All current holders — sweeper, admin shell and tests. */
    public List<ResourceLeaseHolderDocument> listHolders() {
        return mongoTemplate.findAll(ResourceLeaseHolderDocument.class);
    }

    /** The holders of one tenant — the tenant admin's view. */
    public List<ResourceLeaseHolderDocument> listHolders(String tenantId) {
        return mongoTemplate.find(
                Query.query(Criteria.where("tenantId").is(tenantId)), ResourceLeaseHolderDocument.class);
    }

    /** One holder by token, regardless of tenant — admin shell. */
    public Optional<ResourceLeaseHolderDocument> findHolder(String token) {
        return Optional.ofNullable(mongoTemplate.findById(token, ResourceLeaseHolderDocument.class));
    }

    /** All funnel counters, key taken apart. */
    public List<LeaseCounter> listCounters() {
        return mongoTemplate.findAll(ResourceLeaseCounterDocument.class).stream()
                .map(LeaseCounter::of)
                .toList();
    }

    /**
     * The counters that count only this tenant's holders — tenant, project
     * and user level. The global level is left out: its occupancy sums up
     * every tenant and is not the tenant admin's to see.
     */
    public List<LeaseCounter> listCounters(String tenantId) {
        return listCounters().stream().filter(c -> c.belongsToTenant(tenantId)).toList();
    }

    // ──────────────── internals ────────────────

    /**
     * Takes one slot on a level: {@code true} when taken, {@code false}
     * when the level is full. The hot path is one conditional increment
     * without upsert — a missing document only means "never used (or
     * reaped by reconcile)", which {@link #ensureCounter} fixes before a
     * second try. An upsert inside the conditional increment would turn a
     * concurrent first use into a false busy: the losing insert collides
     * on {@code _id}, and Mongo only retries such upserts when the filter
     * is a pure {@code _id} equality.
     */
    private boolean incCounter(String key, long cap) {
        if (incExisting(key, cap)) return true;
        ensureCounter(key);
        return incExisting(key, cap);
    }

    private boolean incExisting(String key, long cap) {
        return mongoTemplate.findAndModify(
                        Query.query(Criteria.where("_id").is(key).and("count").lt(cap)),
                        new Update().inc("count", 1).currentDate("updatedAt"),
                        ResourceLeaseCounterDocument.class)
                != null;
    }

    /** Creates the counter at zero if absent — a pure {@code _id} upsert, idempotent across pods. */
    private void ensureCounter(String key) {
        try {
            mongoTemplate.upsert(
                    Query.query(Criteria.where("_id").is(key)),
                    new Update().setOnInsert("count", 0L).setOnInsert("updatedAt", Instant.now()),
                    ResourceLeaseCounterDocument.class);
        } catch (DuplicateKeyException e) {
            // Another pod created it between our read and insert — exists now.
        }
    }

    private void decCounter(String key) {
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(key)),
                new Update().inc("count", -1).currentDate("updatedAt"),
                ResourceLeaseCounterDocument.class);
    }

    private String diagnose(LeaseDefinition def, BusySignal busy) {
        Long count = null;
        try {
            ResourceLeaseCounterDocument counter =
                    mongoTemplate.findById(busy.level.key(), ResourceLeaseCounterDocument.class);
            count = counter == null ? null : counter.getCount();
        } catch (RuntimeException ignored) {
            // Diagnosis is best-effort — the busy decision stands.
        }
        String occupancy = count == null ? "" : " (" + count + "/" + busy.level.cap() + " held)";
        return "LEASE_BUSY: '" + def.resourceKey() + "' level '" + busy.level.label() + "' is full" + occupancy
                + " — retry later";
    }

    private static void sleepWithJitter(long deadline) {
        long budget = Math.min(deadline - System.currentTimeMillis(), 200);
        try {
            Thread.sleep(50 + ThreadLocalRandom.current().nextLong(Math.max(1, budget)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LeaseUnavailableException("interrupted while waiting for a lease slot", e);
        }
    }

    private static Query byToken(String token) {
        return Query.query(Criteria.where("_id").is(token));
    }

    private static String counterKey(String resource, String level, String scope) {
        return resource + SEPARATOR + level + SEPARATOR + scope;
    }

    /** Live lease handed out by {@link #acquire} — delegates to the service. */
    private final class HeldLease implements Lease {
        private final String token;
        private final String resource;

        private HeldLease(String token, String resource) {
            this.token = token;
            this.resource = resource;
        }

        @Override
        public String holderToken() {
            return token;
        }

        @Override
        public void check() {
            ResourceLeaseService.this.check(token, resource);
        }

        @Override
        public void updatePayload(Map<String, Object> payload) {
            ResourceLeaseService.this.updatePayload(token, resource, payload);
        }

        @Override
        public void release() {
            ResourceLeaseService.this.release(token, resource);
        }

        @Override
        public boolean isNoop() {
            return false;
        }

        @Override
        public String toString() {
            return "Lease[" + resource + " " + token + "]";
        }
    }
}
