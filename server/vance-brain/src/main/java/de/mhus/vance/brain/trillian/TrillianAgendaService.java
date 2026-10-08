package de.mhus.vance.brain.trillian;

import de.mhus.vance.brain.trillian.nature.SelfCheckFinding;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * The framework half of the self-check: what the loop's own documents say is
 * due, and — when it has been quiet long enough — the standing goals that
 * make an idle hour worth spending.
 *
 * <p>The Nature half ({@code TrillianNature.selfCheckFindings}) is composed
 * on top of this by {@link TrillianHeartbeatTick}; both ride the same gate,
 * so a wakeup with nothing in it still costs no turn.
 *
 * <p><b>Java decides <em>that</em>, the loop decides <em>what</em>.</b> The
 * findings here are plain comparisons ({@code due <= now}, quiet rung) —
 * what a due appointment <em>means</em> is the model's business in the turn
 * that follows.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TrillianAgendaService {

    /** Standing goals, one file per Trillian in its home. */
    public static final String GOALS_PATH = "_vance/trillian/goals.yaml";

    /**
     * Cadence rung at which silence itself becomes a finding. Rung 3 is the
     * 60-minute step of the wakeup ladder — an hour of quiet is the floor
     * agreed for "bored", so an idle Trillian stirs rarely, and never in the
     * minutes-tick nervousness the ladder starts at.
     */
    private static final int BORED_RUNG = 3;

    private final TrillianScheduleStore scheduleStore;
    private final TrillianWakeupService wakeupService;
    private final DocumentService documentService;
    private final de.mhus.vance.shared.thinkprocess.ThinkProcessService thinkProcessService;

    /**
     * What the loop's own documents say right now: one finding per due
     * schedule, plus at most one {@code [bored]} when {@code boredAllowed}
     * (the Nature opted in, A5), it has been quiet long enough and there are
     * standing goals. Side-effect free by contract — the update happens in
     * {@link #delivered}.
     */
    public List<SelfCheckFinding> findings(ThinkProcessDocument loop, boolean boredAllowed) {
        List<SelfCheckFinding> out = new ArrayList<>(scheduleFindings(loop));
        String home = homeOf(loop);
        if (home == null || !boredAllowed) {
            return out;
        }
        if (wakeupService.cadenceStep(loop) >= BORED_RUNG) {
            String goals = goals(loop.getTenantId(), home);
            if (goals != null && !goals.isBlank()) {
                out.add(new SelfCheckFinding(
                        SelfCheckFinding.Kind.BORED,
                        "quiet",
                        GOALS_PATH,
                        "nothing arrived for a while; standing goals are up: " + firstLine(goals)));
            }
        }
        return out;
    }

    /**
     * Only the due appointments — what a schedule-driven wakeup carries. A
     * schedule fires on the scan grid, not on the self-check ladder, so this
     * is asked on its own whenever the loop's schedule marker comes due.
     */
    public List<SelfCheckFinding> scheduleFindings(ThinkProcessDocument loop) {
        List<SelfCheckFinding> out = new ArrayList<>();
        String home = homeOf(loop);
        if (home == null) {
            return out;
        }
        Instant now = Instant.now();
        for (TrillianScheduleStore.Schedule s : scheduleStore.list(loop.getTenantId(), home)) {
            if (!s.enabled() || s.due().isAfter(now)) {
                continue;
            }
            out.add(new SelfCheckFinding(
                    SelfCheckFinding.Kind.SCHEDULE_DUE,
                    s.name(),
                    s.name(),
                    firstLine(s.label() == null ? s.payload() : s.label())));
        }
        return out;
    }

    /**
     * The findings were handed to the loop: rewrite {@code due} for the
     * schedules that were <em>reported</em> — not for whatever is due by
     * now, which may include an entry that came due after the findings were
     * read and was never shown to the loop. Recurrence re-anchors <em>from
     * now</em> (D10), so a missed run never materialises; a one-shot
     * disables itself instead. Per entry: one that cannot be written does
     * not keep the others from moving on. Then the schedule marker on the
     * loop is recomputed.
     */
    public void delivered(ThinkProcessDocument loop, List<SelfCheckFinding> findings) {
        String home = homeOf(loop);
        if (home == null) {
            return;
        }
        Instant now = Instant.now();
        for (SelfCheckFinding finding : findings) {
            if (finding.kind() != SelfCheckFinding.Kind.SCHEDULE_DUE) {
                continue;
            }
            try {
                scheduleStore
                        .find(loop.getTenantId(), home, finding.subjectName())
                        .ifPresent(s -> scheduleStore.save(loop.getTenantId(), home, fired(s, now)));
            } catch (RuntimeException e) {
                log.warn(
                        "Trillian: could not advance schedule '{}' in '{}': {}",
                        finding.subjectName(),
                        home,
                        e.toString());
            }
        }
        refreshScheduleMarker(loop);
    }

    private static TrillianScheduleStore.Schedule fired(TrillianScheduleStore.Schedule s, Instant now) {
        if (s.next() != null) {
            return new TrillianScheduleStore.Schedule(
                    s.name(),
                    s.label(),
                    TrillianScheduleStore.nextDue(s.next(), now),
                    s.next(),
                    s.payload(),
                    true,
                    now);
        }
        return new TrillianScheduleStore.Schedule(s.name(), s.label(), s.due(), null, s.payload(), false, now);
    }

    /**
     * Same, for the loop process with this id — what the {@code schedule_*}
     * tools call after a change, holding only their own process id. A
     * missing id or process is a no-op: the heartbeat computes the marker of
     * any loop that has none.
     */
    public void refreshScheduleMarker(@Nullable String loopProcessId) {
        if (loopProcessId == null) {
            return;
        }
        thinkProcessService.findById(loopProcessId).ifPresent(this::refreshScheduleMarker);
    }

    /**
     * Writes the earliest enabled {@code due} of the loop's schedules onto
     * the loop process, so the heartbeat can tell from the process alone
     * whether an appointment is up — no document listing per loop per tick.
     * Called after every change to the schedules and after every delivery.
     */
    public void refreshScheduleMarker(ThinkProcessDocument loop) {
        String home = homeOf(loop);
        if (home == null || loop.getId() == null) {
            return;
        }
        Instant earliest = null;
        for (TrillianScheduleStore.Schedule s : scheduleStore.list(loop.getTenantId(), home)) {
            if (s.enabled() && (earliest == null || s.due().isBefore(earliest))) {
                earliest = s.due();
            }
        }
        wakeupService.setScheduleMarker(loop.getId(), earliest);
    }

    /** The standing goals document, or {@code null} when there is none. */
    public @Nullable String goals(String tenantId, String home) {
        try {
            return documentService
                    .findByPath(tenantId, home, GOALS_PATH)
                    .map(this.documentService::readContent)
                    .orElse(null);
        } catch (RuntimeException e) {
            log.warn("Trillian: could not read goals in '{}': {}", home, e.toString());
            return null;
        }
    }

    /**
     * The hub the loop's durable state lives in — derived from the account
     * name on the process, so the caller never has to know where home is.
     */
    private static @Nullable String homeOf(ThinkProcessDocument loop) {
        Map<String, Object> params = loop.getEngineParams();
        Object account = params == null ? null : params.get(TrillianSessionBootstrapper.PARAM_TRILLIAN_USER_NAME);
        return account == null ? null : HomeBootstrapService.hubProjectName(account.toString());
    }

    private static String firstLine(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String flat = text.strip();
        int nl = flat.indexOf('\n');
        String line = nl < 0 ? flat : flat.substring(0, nl);
        return line.length() <= 160 ? line : line.substring(0, 160) + "…";
    }
}
