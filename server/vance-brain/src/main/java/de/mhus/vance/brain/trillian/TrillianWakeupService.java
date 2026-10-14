package de.mhus.vance.brain.trillian;

import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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
 * <p>The schedule decelerates — by default 10, 20, 40, 60 minutes, and at
 * most every two hours at night — and resets to the first step whenever
 * something real happens. Exact times do not matter; what matters is
 * that he looks regularly. A tick that runs a minute late has lost
 * nothing.
 *
 * <p><b>Per Trillian.</b> Trillians differ in how attentive they need to
 * be, so the ladder and the night minimum are settings
 * ({@link #LADDER_KEY}, {@link #NIGHT_MINUTES_KEY}) resolved through the
 * user/project cascade — tenant default, overridden on the account or on its
 * hub project. A malformed value falls back to the default with a WARN: a
 * Trillian that never wakes again is worse than one on the default rhythm.
 *
 * <p><b>Woken, not polled, for a conversation.</b> A contribution to a
 * single-headed Trillian's conversation makes its self-check due now
 * ({@link #wakeNow}) — the next heartbeat round delivers it, and the unread
 * thread is the finding. The ladder stays what it is for: noticing what
 * nobody reports.
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
     * engineParamOverrides key: {@code true} while a {@link #wakeNow} is
     * owed. A due time alone does not survive the loop's own yield point —
     * {@link #arm} recomputes it from the ladder, and {@link #disarm} clears
     * it while a worker runs — so a contribution arriving mid-turn would wait
     * for the ladder again. The flag keeps the self-check due until a turn
     * has started ({@link #clearWakeRequest}) or the heartbeat has handed the
     * self-check over.
     */
    public static final String PARAM_WAKE_REQUESTED = "trillianWakeRequested";

    /**
     * engineParamOverrides key: epoch millis of the earliest enabled schedule
     * in the loop's home. Independent of the ladder — an appointment fires on
     * the scan grid, also while a worker runs and the ladder is disarmed.
     * {@value #NO_SCHEDULE} means "computed, nothing scheduled"; an absent
     * key means "never computed" and makes the heartbeat compute it.
     */
    public static final String PARAM_NEXT_SCHEDULE_AT = "trillianNextScheduleAt";

    /** Marker value for "computed, no enabled schedule". */
    public static final long NO_SCHEDULE = -1L;

    /** Setting: minutes per quiet round, comma-separated (e.g. {@code 10,20,40,60}). */
    public static final String LADDER_KEY = "trillian.wakeup.ladder";

    /** Setting: the minimum gap at night (20:00–08:00), in minutes. */
    public static final String NIGHT_MINUTES_KEY = "trillian.wakeup.night-minutes";

    /** One Trillian's rhythm: the ladder and the night minimum. */
    public record Cadence(List<Integer> ladder, int nightMinutes) {

        public Cadence {
            ladder = List.copyOf(ladder);
        }

        /** The built-in rhythm — see {@link #LADDER} and {@link #NIGHT_MINUTES}. */
        public static final Cadence DEFAULT = new Cadence(LADDER, NIGHT_MINUTES);
    }

    /**
     * Minutes between self-checks, per consecutive quiet round. Starts at
     * ten rather than five: the first wakeup after a task is rarely the
     * useful one, and a Trillian that stirs every five minutes reads as
     * nervous rather than attentive.
     */
    static final List<Integer> LADDER = List.of(10, 20, 40, 60);

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
    private final SettingService settingService;

    /**
     * Arms the next self-check, or clears it when the loop has nothing to
     * come back to.
     *
     * <p>Called at the loop's yield point. {@code step} is carried in the
     * process itself, so a brain restart resumes the schedule instead of
     * forgetting it.
     */
    public void arm(ThinkProcessDocument loop, ZoneId zone) {
        if (isWakeRequested(loop)) {
            // Somebody wrote while the loop was busy: the self-check stays
            // due, whatever the ladder or a running worker would say.
            keepDue(loop.getId());
            return;
        }
        if (!shouldArm(loop)) {
            log.trace(
                    "Trillian wakeup not armed id='{}' — status {} or a worker is running",
                    loop.getId(),
                    loop.getStatus());
            disarm(loop);
            return;
        }
        Cadence cadence = cadenceOf(loop);
        int step = currentStep(loop, cadence);
        int nominal = minutesFor(cadence, step, LocalTime.now(zone));
        Duration gap = jittered(Duration.ofMinutes(nominal));
        Instant next = Instant.now().plus(gap);
        thinkProcessService.setEngineParamOverride(loop.getId(), PARAM_NEXT_WAKEUP_AT, next.toEpochMilli());
        thinkProcessService.setEngineParamOverride(
                loop.getId(),
                PARAM_WAKEUP_STEP,
                Math.min(step + 1, cadence.ladder().size() - 1));
        log.trace(
                "Trillian wakeup armed id='{}' in {} min (step {}, nominal {})",
                loop.getId(),
                gap.toMinutes(),
                step,
                nominal);
    }

    /**
     * Makes the self-check due now and resets the cadence — somebody wrote
     * to this Trillian, and waiting for the ladder would leave them up to an
     * hour without an answer. The next heartbeat round wakes the loop (when
     * it is quiet; a busy loop sees the thread at its next turn anyway), and
     * costs a turn only because the unread thread is a finding.
     */
    public void wakeNow(String loopProcessId) {
        thinkProcessService.setEngineParamOverride(loopProcessId, PARAM_WAKE_REQUESTED, true);
        keepDue(loopProcessId);
        resetCadence(loopProcessId);
        log.trace("Trillian wakeup due now id='{}'", loopProcessId);
    }

    /**
     * Drops an owed {@link #wakeNow} — a turn has started and will see what
     * the request was about, or the heartbeat delivered the self-check (or
     * found nothing to show). A contribution after this point requests anew.
     */
    public void clearWakeRequest(String loopProcessId) {
        thinkProcessService.setEngineParamOverride(loopProcessId, PARAM_WAKE_REQUESTED, null);
    }

    /** Whether a {@link #wakeNow} is still owed. */
    public boolean isWakeRequested(ThinkProcessDocument loop) {
        Map<String, Object> overrides = loop.getEngineParamOverrides();
        return overrides != null && Boolean.TRUE.equals(overrides.get(PARAM_WAKE_REQUESTED));
    }

    private void keepDue(String loopProcessId) {
        thinkProcessService.setEngineParamOverride(
                loopProcessId, PARAM_NEXT_WAKEUP_AT, Instant.now().toEpochMilli());
    }

    /**
     * This loop's rhythm from the settings cascade — tenant, the Trillian's
     * account, its hub project; the built-in default where nothing (valid) is
     * set.
     */
    public Cadence cadenceOf(ThinkProcessDocument loop) {
        String tenantId = loop.getTenantId();
        String account = TrillianHeadService.accountOf(loop);
        String ladderRaw = settingService.getStringValueUserProjectCascade(
                tenantId, account.isBlank() ? null : account, loop.getProjectId(), /*thinkProcessId*/ null, LADDER_KEY);
        String nightRaw = settingService.getStringValueUserProjectCascade(
                tenantId,
                account.isBlank() ? null : account,
                loop.getProjectId(),
                /*thinkProcessId*/ null,
                NIGHT_MINUTES_KEY);
        return new Cadence(parseLadder(ladderRaw, loop.getId()), parseMinutes(nightRaw, NIGHT_MINUTES, loop.getId()));
    }

    static List<Integer> parseLadder(@Nullable String raw, String loopId) {
        if (raw == null || raw.isBlank()) {
            return LADDER;
        }
        try {
            List<Integer> ladder = java.util.Arrays.stream(raw.split(","))
                    .map(String::trim)
                    .map(Integer::valueOf)
                    .toList();
            if (!ladder.isEmpty() && ladder.stream().allMatch(m -> m > 0)) {
                return ladder;
            }
        } catch (NumberFormatException e) {
            // falls through to the warning below
        }
        log.warn(
                "Trillian wakeup: {}='{}' on loop '{}' is not a list of positive minutes — default {}",
                LADDER_KEY,
                raw,
                loopId,
                LADDER);
        return LADDER;
    }

    static int parseMinutes(@Nullable String raw, int fallback, String loopId) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int minutes = Integer.parseInt(raw.trim());
            if (minutes > 0) {
                return minutes;
            }
        } catch (NumberFormatException e) {
            // falls through to the warning below
        }
        log.warn(
                "Trillian wakeup: {}='{}' on loop '{}' is not a positive number of minutes — default {}",
                NIGHT_MINUTES_KEY,
                raw,
                loopId,
                fallback);
        return fallback;
    }

    /**
     * Clears a pending self-check — something is in flight after all. An
     * owed {@link #wakeNow} is not something in flight: it stays due.
     */
    public void disarm(ThinkProcessDocument loop) {
        if (isWakeRequested(loop)) {
            log.trace("Trillian wakeup not disarmed id='{}' — a wake is owed", loop.getId());
            keepDue(loop.getId());
            return;
        }
        log.trace("Trillian wakeup disarmed id='{}'", loop.getId());
        thinkProcessService.setEngineParamOverride(loop.getId(), PARAM_NEXT_WAKEUP_AT, null);
    }

    /**
     * Clears the self-check because the heartbeat is handing it over right
     * now — the owed wake included, unlike {@link #disarm}: this is the wake.
     */
    public void consumeWake(ThinkProcessDocument loop) {
        clearWakeRequest(loop.getId());
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

    /** The current rung (0-based) of the quiet-ladder. */
    public int cadenceStep(ThinkProcessDocument loop) {
        return currentStep(loop, cadenceOf(loop));
    }

    /**
     * The armed self-check instant, or {@code null} when nothing is armed.
     * Read-only view for status and activity snapshots.
     */
    public @Nullable Instant nextWakeupAt(ThinkProcessDocument loop) {
        Long at = longOverride(loop, PARAM_NEXT_WAKEUP_AT);
        return at == null ? null : Instant.ofEpochMilli(at);
    }

    /** Sets the schedule marker; {@code null} records "nothing scheduled". */
    public void setScheduleMarker(String loopProcessId, @Nullable Instant earliestDue) {
        thinkProcessService.setEngineParamOverride(
                loopProcessId, PARAM_NEXT_SCHEDULE_AT, earliestDue == null ? NO_SCHEDULE : earliestDue.toEpochMilli());
    }

    /** Whether the schedule marker was ever computed for this loop. */
    public boolean hasScheduleMarker(ThinkProcessDocument loop) {
        return longOverride(loop, PARAM_NEXT_SCHEDULE_AT) != null;
    }

    /** The earliest schedule due on this loop, or {@code null}. */
    public @Nullable Instant scheduleMarker(ThinkProcessDocument loop) {
        Long at = longOverride(loop, PARAM_NEXT_SCHEDULE_AT);
        return at == null || at < 0 ? null : Instant.ofEpochMilli(at);
    }

    /** Whether one of the loop's schedules has come due. */
    public boolean isScheduleDue(ThinkProcessDocument loop, Instant now) {
        Instant at = scheduleMarker(loop);
        return at != null && !at.isAfter(now);
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

    /**
     * Minutes for this step of {@code cadence}, capped harder during the
     * night — against a given time of day, so the ladder and the night cap
     * can be checked without the caller's wall clock deciding which of the
     * two answers is correct.
     */
    static int minutesFor(Cadence cadence, int step, LocalTime now) {
        List<Integer> ladder = cadence.ladder();
        int minutes = ladder.get(Math.max(0, Math.min(step, ladder.size() - 1)));
        return isNight(now) ? Math.max(minutes, cadence.nightMinutes()) : minutes;
    }

    static boolean isNight(LocalTime now) {
        return now.isAfter(NIGHT_FROM) || now.isBefore(NIGHT_UNTIL);
    }

    private static int currentStep(ThinkProcessDocument loop, Cadence cadence) {
        Long step = longOverride(loop, PARAM_WAKEUP_STEP);
        return step == null
                ? 0
                : (int) Math.max(0, Math.min(step, cadence.ladder().size() - 1));
    }

    private static @Nullable Long longOverride(ThinkProcessDocument loop, String key) {
        Map<String, Object> overrides = loop.getEngineParamOverrides();
        if (overrides == null) {
            return null;
        }
        Object raw = overrides.get(key);
        return raw instanceof Number n ? n.longValue() : null;
    }

    /**
     * The live loop processes in {@code projects} — the projects the calling
     * pod holds. Loops live in their account's hub, so whatever else is held
     * simply matches nothing. One indexed query per tick, scoped to the pod's
     * own projects rather than the cluster.
     */
    public List<ThinkProcessDocument> liveLoopsIn(Collection<ProjectDocument> projects) {
        Map<String, List<String>> byTenant = projects.stream()
                .collect(Collectors.groupingBy(
                        ProjectDocument::getTenantId,
                        Collectors.mapping(ProjectDocument::getName, Collectors.toList())));
        return thinkProcessService.findLiveByEngineInProjects(TrillianUserEngine.NAME, byTenant);
    }
}
