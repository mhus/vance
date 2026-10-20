package de.mhus.vance.brain.ai.power;

import de.mhus.vance.shared.cache.CronTenantCache;
import de.mhus.vance.shared.llmusage.LlmPowerService;
import de.mhus.vance.shared.llmusage.LlmPowerState;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The LLM power state of every tenant this pod calls models for, held in RAM
 * via {@link CronTenantCache}.
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
 * #verifyAt()}). A model call already on the wire is not interrupted — the
 * switch decides about the next attempt.
 *
 * <h2>Failure</h2>
 *
 * A failed first load is logged and the call is admitted without caching —
 * the next call retries. A failed tick reload keeps the last known state.
 * Renames and deletes are handled by {@code LlmPowerUserDataHandler}, which
 * keeps the switch in step with the login.
 */
@Service
@Slf4j
public class LlmPowerSwitch {

    /** A tenant not asked for this long is dropped and reloaded on its next call. */
    static final Duration IDLE_EVICTION = Duration.ofMinutes(10);

    /** Default refresh: at second 00 of every minute, the same instant on every pod. */
    static final String DEFAULT_CRON = "0 * * * * *";

    private final LlmPowerService powerService;
    private final CronTenantCache<String, LlmPowerState> cache;

    @Autowired
    public LlmPowerSwitch(
            LlmPowerService powerService, @Value("${vance.llm.power.refresh-cron:" + DEFAULT_CRON + "}") String cron) {
        this(powerService, cron, Clock.systemUTC());
    }

    LlmPowerSwitch(LlmPowerService powerService, String cron, Clock clock) {
        this.powerService = powerService;
        this.cache = new CronTenantCache<>(cron, IDLE_EVICTION, clock, powerService::load);
    }

    @PostConstruct
    void startRefresh() {
        cache.start();
    }

    @PreDestroy
    void stopRefresh() {
        cache.stop();
    }

    /** When the admin view should check that a change has reached every pod. */
    public Instant verifyAt() {
        return cache.verifyAt();
    }

    /** The next refresh tick on the wall clock. */
    public Instant nextRefreshAt() {
        return cache.nextRefreshAt();
    }

    /** The pod's clock, for the admin view to schedule its check relative to. */
    public Instant now() {
        return cache.now();
    }

    /** The current state of {@code tenantId} — from RAM except for the first call. */
    public LlmPowerState state(String tenantId) {
        // getOrLoad, not peek: a hit has to refresh lastAsked, otherwise an
        // active tenant is evicted after IDLE_EVICTION and its next load could
        // land in a Mongo hiccup — admitting a switched-off tenant.
        try {
            return cache.getOrLoad(tenantId);
        } catch (RuntimeException e) {
            log.warn("LLM power state of tenant '{}' not readable, admitting: {}", tenantId, e.toString());
            return LlmPowerState.ALL_ON;
        }
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
        return cache.reload(tenantId);
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
        return cache.peek(tenantId);
    }

    /**
     * The refresh tick: reload every tenant asked for recently, drop the rest.
     * Runs on the dedicated scheduler started by {@link #startRefresh}.
     */
    void refresh() {
        cache.tick();
    }
}
