package de.mhus.vance.brain.ai.power;

import de.mhus.vance.shared.llmusage.LlmPowerService;
import de.mhus.vance.shared.llmusage.LlmPowerState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
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
    private final Map<String, Entry> states = new ConcurrentHashMap<>();

    @Autowired
    public LlmPowerSwitch(
            LlmPowerService powerService, @Value("${vance.llm.power.refresh-cron:" + DEFAULT_CRON + "}") String cron) {
        this(powerService, cron, Clock.systemUTC());
    }

    LlmPowerSwitch(LlmPowerService powerService, String cron, Clock clock) {
        this.powerService = powerService;
        this.refreshCron = CronExpression.parse(cron);
        this.clock = clock;
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
        LlmPowerState loaded;
        try {
            loaded = powerService.load(tenantId);
        } catch (RuntimeException e) {
            log.warn("LLM power state of tenant '{}' not readable, admitting: {}", tenantId, e.toString());
            return LlmPowerState.ALL_ON;
        }
        remember(tenantId, loaded, now);
        return loaded;
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
        LlmPowerState loaded = powerService.load(tenantId);
        remember(tenantId, loaded, clock.instant());
        return loaded;
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

    @Scheduled(cron = "${vance.llm.power.refresh-cron:" + DEFAULT_CRON + "}")
    public void refresh() {
        Instant evictBefore = clock.instant().minus(IDLE_EVICTION);
        for (Map.Entry<String, Entry> e : states.entrySet()) {
            Entry entry = e.getValue();
            if (entry.lastAsked.isBefore(evictBefore)) {
                states.remove(e.getKey(), entry);
                continue;
            }
            try {
                entry.state = powerService.load(e.getKey());
            } catch (RuntimeException ex) {
                log.warn("LLM power refresh of tenant '{}' failed, keeping last state: {}", e.getKey(), ex.toString());
            }
        }
    }

    private void remember(String tenantId, LlmPowerState state, Instant now) {
        Entry entry = states.get(tenantId);
        if (entry != null) {
            entry.state = state;
            entry.lastAsked = now;
            return;
        }
        if (states.size() >= MAX_TENANTS) {
            states.clear();
        }
        states.put(tenantId, new Entry(state, now));
    }

    /** Mutable on purpose: the refresh swaps the state, the call path stamps the time. */
    private static final class Entry {
        volatile LlmPowerState state;
        volatile Instant lastAsked;

        Entry(LlmPowerState state, Instant lastAsked) {
            this.state = state;
            this.lastAsked = lastAsked;
        }
    }
}
