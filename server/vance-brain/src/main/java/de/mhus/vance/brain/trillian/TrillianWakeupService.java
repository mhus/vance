package de.mhus.vance.brain.trillian;

import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Decides when a Trillian user-loop should wake itself up.
 *
 * <p>A Trillian that only ever reacts is an inbox with opinions. The
 * point of waking it is that it notices things nobody pushed at it: a
 * worker parked on a question nobody answered, a promise it made, work
 * it said it would come back to.
 *
 * <p><b>Only in silence.</b> While the loop is working, nothing is
 * armed. A running per-task worker will emit a terminal event by itself,
 * and a timer next to it would be a second alarm for the same
 * appointment. The clock therefore measures <em>silence</em>, not time
 * since the last turn — a busy Trillian is never woken by it.
 *
 * <p><b>Except when the silence is the problem.</b> A worker sitting
 * IDLE on a question is exactly the case that produces no event ever.
 * That is not "in flight", that is stuck, and it is the first thing a
 * wakeup is good for.
 *
 * <p>The schedule decelerates — 10, 20, 40, 60 minutes, and at most
 * every two hours at night — and resets to the first step whenever
 * something real happens. Exact times do not matter; what matters is
 * that he looks regularly. A tick that runs a minute late has lost
 * nothing.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TrillianWakeupService {

    /**
     * The command a woken loop finds in its inbox. Named rather than
     * empty, so the loop can tell "I woke because the clock said so"
     * from "somebody asked me something" — the two call for very
     * different turns.
     */
    public static final String COMMAND_SELF_CHECK = "trillian_self_check";

    /** ExternalCommand param carrying the rendered findings. */
    public static final String PARAM_FINDINGS = "findings";

    /** engineParamOverrides key: epoch millis of the next self-check. */
    public static final String PARAM_NEXT_WAKEUP_AT = "trillianNextWakeupAt";
    /** engineParamOverrides key: index into {@link #LADDER}. */
    public static final String PARAM_WAKEUP_STEP = "trillianWakeupStep";

    /**
     * Minutes between self-checks, per consecutive quiet round. Starts at
     * ten rather than five: the first wakeup after a task is rarely the
     * useful one, and a Trillian that stirs every five minutes reads as
     * nervous rather than attentive.
     */
    static final int[] LADDER = {10, 20, 40, 60};

    /**
     * How far an interval may stray from its nominal length, in either
     * direction.
     *
     * <p>Without it every Trillian on a pod that started together stays
     * in lockstep for good — they were armed in the same second and the
     * ladder is deterministic, so they wake in the same second forever,
     * and a human with three of them gets three nudges at once.
     *
     * <p>Twenty percent rather than the full-jitter of network retries
     * (a uniform draw over the whole interval). The contention here is
     * not a server being hammered but a rhythm that should stay
     * recognisable: "about every twenty minutes" survives ±20%, and does
     * not survive "somewhere between now and twenty minutes".
     */
    static final double JITTER = 0.2;

    /** Night cap. Nobody is waiting for an answer at 3 a.m. */
    static final int NIGHT_MINUTES = 120;
    static final LocalTime NIGHT_FROM = LocalTime.of(20, 0);
    static final LocalTime NIGHT_UNTIL = LocalTime.of(8, 0);

    /** Only spreads alarm clocks — nothing here needs to be unguessable. */
    private final java.util.Random random = new java.util.Random();

    private final ThinkProcessService thinkProcessService;

    /**
     * Arms the next self-check, or clears it when the loop has nothing to
     * come back to.
     *
     * <p>Called at the loop's yield point. {@code step} is carried in the
     * process itself, so a brain restart resumes the schedule instead of
     * forgetting it.
     */
    public void arm(ThinkProcessDocument loop, ZoneId zone) {
        if (!shouldArm(loop)) {
            log.trace("Trillian wakeup not armed id='{}' — status {} or a worker is running",
                    loop.getId(), loop.getStatus());
            disarm(loop);
            return;
        }
        int step = currentStep(loop);
        Duration gap = jittered(Duration.ofMinutes(minutesFor(step, zone)));
        Instant next = Instant.now().plus(gap);
        thinkProcessService.setEngineParamOverride(
                loop.getId(), PARAM_NEXT_WAKEUP_AT, next.toEpochMilli());
        thinkProcessService.setEngineParamOverride(
                loop.getId(), PARAM_WAKEUP_STEP, Math.min(step + 1, LADDER.length - 1));
        log.trace("Trillian wakeup armed id='{}' in {} min (step {}, nominal {})",
                loop.getId(), gap.toMinutes(), step, minutesFor(step, zone));
    }

    /** Clears a pending self-check — something is in flight after all. */
    public void disarm(ThinkProcessDocument loop) {
        log.trace("Trillian wakeup disarmed id='{}'", loop.getId());
        thinkProcessService.setEngineParamOverride(loop.getId(), PARAM_NEXT_WAKEUP_AT, null);
    }

    /**
     * Back to the first step, because something real happened. Called
     * when a turn was driven by anything other than a self-check —
     * otherwise a Trillian that has been quiet for a day would stay on
     * the two-hour cadence right through the next busy afternoon.
     */
    public void resetCadence(String loopProcessId) {
        thinkProcessService.setEngineParamOverride(loopProcessId, PARAM_WAKEUP_STEP, 0);
    }

    /** Whether an appointment exists at all — armed, due or not. */
    public boolean isArmed(ThinkProcessDocument loop) {
        return longOverride(loop, PARAM_NEXT_WAKEUP_AT) != null;
    }

    /** Whether this loop's self-check is due. */
    public boolean isDue(ThinkProcessDocument loop, Instant now) {
        Long at = longOverride(loop, PARAM_NEXT_WAKEUP_AT);
        return at != null && at <= now.toEpochMilli();
    }

    /**
     * Whether a self-check is worth scheduling at all.
     *
     * <p>No, while a per-task worker is RUNNING — it will report by
     * itself. Yes, when one is parked IDLE, because nothing will ever
     * arrive on its own; that worker is waiting for an answer somebody
     * owes it. Yes, when the loop is simply quiet, because that is when
     * the things it promised go unnoticed.
     */
    boolean shouldArm(ThinkProcessDocument loop) {
        if (loop.getStatus() == ThinkProcessStatus.CLOSED
                || loop.getStatus() == ThinkProcessStatus.PAUSED
                || loop.getStatus() == ThinkProcessStatus.SUSPENDED) {
            return false;
        }
        for (ThinkProcessDocument child : thinkProcessService.findByParentProcessId(loop.getId())) {
            if (child.getStatus() == ThinkProcessStatus.RUNNING) {
                return false;
            }
        }
        return true;
    }

    /**
     * The nominal gap, moved by up to {@link #JITTER} in either
     * direction. Never below a minute, so a small interval and an
     * unlucky draw cannot produce a wakeup that is due the moment it is
     * armed.
     */
    Duration jittered(Duration nominal) {
        double factor = 1.0 + (random.nextDouble() * 2.0 - 1.0) * JITTER;
        long millis = Math.round(nominal.toMillis() * factor);
        return Duration.ofMillis(Math.max(millis, Duration.ofMinutes(1).toMillis()));
    }

    /** Minutes for this step, capped harder during the night. */
    int minutesFor(int step, ZoneId zone) {
        int minutes = LADDER[Math.max(0, Math.min(step, LADDER.length - 1))];
        return isNight(LocalTime.now(zone)) ? Math.max(minutes, NIGHT_MINUTES) : minutes;
    }

    static boolean isNight(LocalTime now) {
        return now.isAfter(NIGHT_FROM) || now.isBefore(NIGHT_UNTIL);
    }

    private int currentStep(ThinkProcessDocument loop) {
        Long step = longOverride(loop, PARAM_WAKEUP_STEP);
        return step == null ? 0 : (int) Math.max(0, Math.min(step, LADDER.length - 1));
    }

    private static @Nullable Long longOverride(ThinkProcessDocument loop, String key) {
        Map<String, Object> overrides = loop.getEngineParamOverrides();
        if (overrides == null) {
            return null;
        }
        Object raw = overrides.get(key);
        return raw instanceof Number n ? n.longValue() : null;
    }

    /** Loop processes of a project — the only ones that wake themselves. */
    public List<ThinkProcessDocument> loopsOf(String tenantId, String projectId, int limit) {
        return thinkProcessService.findByProjectAndEngines(
                tenantId, projectId, List.of(TrillianUserEngine.NAME), limit);
    }
}
