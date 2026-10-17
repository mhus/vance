package de.mhus.vance.brain.ai.power;

import de.mhus.vance.shared.llmusage.LlmPowerService;
import de.mhus.vance.shared.llmusage.LlmPowerState;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

/**
 * The LLM power state of every tenant this pod calls models for, held in RAM.
 *
 * <h2>Why a background refresh and not a TTL</h2>
 *
 * The state is asked on every model attempt, retries included. A TTL would put
 * a Mongo read into the call path of whichever attempt happens to find the
 * entry expired; the refresh tick takes that read out of the call path
 * entirely. Only the very first call of a tenant on this pod loads
 * synchronously.
 *
 * <h2>How fast a switch takes effect</h2>
 *
 * On the pod that serves the admin's request: at once, because the write path
 * reloads the tenant here. On every other pod: with the next tick of
 * {@code vance.llm.power.refresh-cron} — by default second 00 of every minute,
 * on the wall clock rather than a delay since boot, so every pod refreshes at
 * the same instant and the admin view knows when to look ({@link
 * #nextRefreshAt}). A model call already on the wire is not interrupted — the
 * switch decides about the next attempt.
 *
 * <h2>Failure</h2>
 *
 * A failed refresh keeps the last known state: a Mongo hiccup must neither
 * switch a tenant off nor switch a switched-off tenant back on. A failed
 * <em>first</em> load admits the call and is not cached, so the next call
 * tries again — without a state there is nothing to enforce, and a pod that
 * cannot read settings cannot do much else either.
 */
@Service
@Slf4j
public class LlmPowerSwitch {

    /** A tenant not asked for this long is dropped and reloaded on its next call. */
    static final Duration IDLE_EVICTION = Duration.ofMinutes(10);

    /** Bound on cached tenants; on overflow the map is cleared — entries reload on demand. */
    static final int MAX_TENANTS = 10_000;

    /**
     * How long after a refresh tick the admin view checks: the tick itself
     * needs a moment to read the store, and checking at the tick would race it.
     */
    public static final Duration VERIFY_GRACE = Duration.ofSeconds(2);

    /** Default refresh: at second 00 of every minute, the same instant on every pod. */
    static final String DEFAULT_CRON = "0 * * * * *";

    private final LlmPowerService powerService;
    private final CronExpression refreshCron;
    private final Clock clock;
    private final String cron;
    private final Map<String, Entry> states = new ConcurrentHashMap<>();
    private final AtomicLong tickets = new AtomicLong();
    private @Nullable ThreadPoolTaskScheduler scheduler;

    @Autowired
    public LlmPowerSwitch(
            LlmPowerService powerService, @Value("${vance.llm.power.refresh-cron:" + DEFAULT_CRON + "}") String cron) {
        this(powerService, cron, Clock.systemUTC());
    }

    LlmPowerSwitch(LlmPowerService powerService, String cron, Clock clock) {
        this.powerService = powerService;
        this.cron = cron;
        this.refreshCron = CronExpression.parse(cron);
        this.clock = clock;
    }

    /** Start the refresh tick on its own thread, in UTC. */
    @PostConstruct
    void startRefresh() {
        ThreadPoolTaskScheduler taskScheduler = new ThreadPoolTaskScheduler();
        taskScheduler.setPoolSize(1);
        taskScheduler.setThreadNamePrefix("llm-power-refresh-");
        taskScheduler.setDaemon(true);
        taskScheduler.initialize();
        taskScheduler.schedule(this::refresh, new CronTrigger(cron, ZoneOffset.UTC));
        this.scheduler = taskScheduler;
    }

    @PreDestroy
    void stopRefresh() {
        ThreadPoolTaskScheduler taskScheduler = scheduler;
        if (taskScheduler != null) {
            taskScheduler.shutdown();
            scheduler = null;
        }
    }

    /**
     * When the admin view should check that a change has reached every pod:
     * the next refresh tick plus {@link #VERIFY_GRACE}.
     */
    public Instant verifyAt() {
        return nextRefreshAt().plus(VERIFY_GRACE);
    }

    /** The next refresh tick on the wall clock. */
    public Instant nextRefreshAt() {
        ZonedDateTime now = ZonedDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        ZonedDateTime next = refreshCron.next(now);
        return next == null ? now.toInstant() : next.toInstant();
    }

    /** The pod's clock, for the admin view to schedule its check relative to. */
    public Instant now() {
        return clock.instant();
    }

    /** The current state of {@code tenantId} — from RAM except for the first call. */
    public LlmPowerState state(String tenantId) {
        Instant now = clock.instant();
        Entry entry = states.get(tenantId);
        if (entry != null) {
            entry.lastAsked = now;
            return entry.state;
        }
        long ticket = tickets.incrementAndGet();
        LlmPowerState loaded;
        try {
            loaded = powerService.load(tenantId);
        } catch (RuntimeException e) {
            log.warn("LLM power state of tenant '{}' not readable, admitting: {}", tenantId, e.toString());
            return LlmPowerState.ALL_ON;
        }
        return remember(tenantId, loaded, ticket, now);
    }

    /** Switch the tenant on or off and make it effective on this pod at once. */
    public LlmPowerState setTenantPower(String tenantId, boolean on, @Nullable String actor) {
        powerService.setTenantPower(tenantId, on, actor);
        log.info("LLM power of tenant '{}' switched {} by {}", tenantId, on ? "on" : "off", actor);
        return reload(tenantId);
    }

    /** Switch one user on or off and make it effective on this pod at once. */
    public LlmPowerState setUserPower(String tenantId, String userId, boolean on, @Nullable String actor) {
        powerService.setUserPower(tenantId, userId, on, actor);
        log.info("LLM power of user '{}' in tenant '{}' switched {} by {}", userId, tenantId, on ? "on" : "off", actor);
        return reload(tenantId);
    }

    /**
     * Read the tenant from the store and put it into this pod's cache — the
     * write path, so a change applies here at once.
     */
    LlmPowerState reload(String tenantId) {
        long ticket = tickets.incrementAndGet();
        LlmPowerState loaded = powerService.load(tenantId);
        return remember(tenantId, loaded, ticket, clock.instant());
    }

    /**
     * What is stored, read now — without touching this pod's cache, so that the
     * admin view can compare it with {@link #cached}. Failures propagate.
     */
    public LlmPowerState stored(String tenantId) {
        return powerService.load(tenantId);
    }

    /**
     * What this pod currently enforces for {@code tenantId}, or {@code null}
     * when it holds nothing — then its next call loads the stored state.
     */
    public @Nullable LlmPowerState cached(String tenantId) {
        Entry entry = states.get(tenantId);
        return entry == null ? null : entry.state;
    }

    /**
     * The refresh tick: reload every tenant asked for recently, drop the rest.
     * Runs on the dedicated scheduler started by {@link #startRefresh}.
     */
    void refresh() {
        Instant evictBefore = clock.instant().minus(IDLE_EVICTION);
        for (Map.Entry<String, Entry> e : states.entrySet()) {
            Entry entry = e.getValue();
            if (entry.lastAsked.isBefore(evictBefore)) {
                states.remove(e.getKey(), entry);
                continue;
            }
            long ticket = tickets.incrementAndGet();
            LlmPowerState loaded;
            try {
                loaded = powerService.load(e.getKey());
            } catch (RuntimeException ex) {
                log.warn("LLM power refresh of tenant '{}' failed, keeping last state: {}", e.getKey(), ex.toString());
                continue;
            }
            // Only replaces what is still cached — a tenant evicted meanwhile stays evicted.
            states.computeIfPresent(
                    e.getKey(),
                    (key, current) -> current.ticket > ticket ? current : new Entry(loaded, ticket, current.lastAsked));
        }
    }

    /**
     * Cache {@code state} as loaded under {@code ticket}, unless a later load
     * is already cached. Returns what is cached afterwards — the newer state
     * wins for the caller too.
     */
    private LlmPowerState remember(String tenantId, LlmPowerState state, long ticket, Instant now) {
        if (states.size() >= MAX_TENANTS && !states.containsKey(tenantId)) {
            states.clear();
        }
        Entry result = states.compute(tenantId, (key, current) -> {
            if (current == null) {
                return new Entry(state, ticket, now);
            }
            if (current.ticket > ticket) {
                current.lastAsked = now;
                return current;
            }
            return new Entry(state, ticket, now);
        });
        return result.state;
    }

    /**
     * One cached state and the ticket of the load that produced it. A new load
     * replaces the entry; only the call path's time stamp is mutable.
     */
    private static final class Entry {
        final LlmPowerState state;
        final long ticket;
        volatile Instant lastAsked;

        Entry(LlmPowerState state, long ticket, Instant lastAsked) {
            this.state = state;
            this.ticket = ticket;
            this.lastAsked = lastAsked;
        }
    }
}
