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

    /**
     * What the loop's own documents say is due right now: one finding per due
     * schedule, plus at most one {@code [bored]} when it has been quiet long
     * enough and there are standing goals. Side-effect free by contract —
     * the update happens in {@link #delivered}.
     */
    public List<SelfCheckFinding> findings(ThinkProcessDocument loop) {
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
     * The findings were handed to the loop: rewrite {@code due} for what
     * fired. Recurrence re-anchors <em>from now</em> (D10), so a missed run
     * never materialises; a one-shot disables itself instead.
     */
    public void delivered(ThinkProcessDocument loop) {
        String home = homeOf(loop);
        if (home == null) {
            return;
        }
        Instant now = Instant.now();
        for (TrillianScheduleStore.Schedule s : scheduleStore.list(loop.getTenantId(), home)) {
            if (!s.enabled() || s.due().isAfter(now)) {
                continue;
            }
            if (s.next() != null) {
                scheduleStore.save(
                        loop.getTenantId(),
                        home,
                        new TrillianScheduleStore.Schedule(
                                s.name(),
                                s.label(),
                                TrillianScheduleStore.nextDue(s.next(), now),
                                s.next(),
                                s.payload(),
                                true,
                                now));
            } else {
                scheduleStore.save(
                        loop.getTenantId(),
                        home,
                        new TrillianScheduleStore.Schedule(
                                s.name(), s.label(), s.due(), null, s.payload(), false, now));
            }
        }
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
