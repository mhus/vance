package de.mhus.vance.brain.trillian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.trillian.nature.SelfCheckFinding;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The framework half of the self-check: due appointments, the opt-in
 * {@code [bored]}, and the bookkeeping after delivery — which must touch
 * exactly what was reported.
 */
class TrillianAgendaServiceTest {

    private static final String ACCOUNT = "_trillian-adam-1234";
    private static final String HOME = "_user_" + ACCOUNT;

    private final TrillianScheduleStore store = mock(TrillianScheduleStore.class);
    private final TrillianWakeupService wakeupService = mock(TrillianWakeupService.class);
    private final DocumentService documentService = mock(DocumentService.class);
    private final ThinkProcessService thinkProcessService = mock(ThinkProcessService.class);
    private final TrillianAgendaService agenda =
            new TrillianAgendaService(store, wakeupService, documentService, thinkProcessService);

    @Test
    void findings_reportOnlyDueEnabledSchedules() {
        Instant past = Instant.now().minusSeconds(60);
        when(store.list("acme", HOME))
                .thenReturn(List.of(
                        schedule("due", past, true),
                        schedule("later", Instant.now().plusSeconds(3600), true),
                        schedule("parked", past, false)));

        assertThat(agenda.findings(loop(), false))
                .extracting(SelfCheckFinding::subjectName)
                .containsExactly("due");
    }

    @Test
    void bored_onlyWhenTheNatureOptedIn() {
        when(store.list("acme", HOME)).thenReturn(List.of());
        when(wakeupService.cadenceStep(any())).thenReturn(3);
        when(documentService.findByPath("acme", HOME, TrillianAgendaService.GOALS_PATH))
                .thenReturn(Optional.of(
                        de.mhus.vance.shared.document.DocumentDocument.builder().build()));
        when(documentService.readContent(any(de.mhus.vance.shared.document.DocumentDocument.class)))
                .thenReturn("- keep the backlog small");

        assertThat(agenda.findings(loop(), false)).isEmpty();
        assertThat(agenda.findings(loop(), true))
                .extracting(SelfCheckFinding::kind)
                .containsExactly(SelfCheckFinding.Kind.BORED);
    }

    @Test
    void delivered_advancesOnlyWhatWasReported() {
        // An entry that came due after the findings were read was never
        // shown to the loop; switching it off would lose it.
        Instant past = Instant.now().minusSeconds(60);
        when(store.find("acme", HOME, "reported")).thenReturn(Optional.of(schedule("reported", past, true)));

        agenda.delivered(loop(), List.of(scheduleFinding("reported")));

        verify(store).save(eq("acme"), eq(HOME), argThat(s -> s.name().equals("reported") && !s.enabled()));
        verify(store, never()).find(eq("acme"), eq(HOME), eq("unreported"));
    }

    @Test
    void delivered_oneBrokenEntry_doesNotHoldBackTheOthers() {
        Instant past = Instant.now().minusSeconds(60);
        when(store.find("acme", HOME, "bad")).thenReturn(Optional.of(schedule("bad", past, true)));
        when(store.find("acme", HOME, "good")).thenReturn(Optional.of(schedule("good", past, true)));
        doThrow(new IllegalStateException("write failed"))
                .when(store)
                .save(eq("acme"), eq(HOME), argThat(s -> s.name().equals("bad")));

        agenda.delivered(loop(), List.of(scheduleFinding("bad"), scheduleFinding("good")));

        verify(store).save(eq("acme"), eq(HOME), argThat(s -> s.name().equals("good")));
    }

    @Test
    void delivered_reAnchorsARecurringEntryFromNow() {
        Instant longAgo = Instant.now().minusSeconds(86_400);
        when(store.find("acme", HOME, "hourly"))
                .thenReturn(Optional.of(
                        new TrillianScheduleStore.Schedule("hourly", null, longAgo, "1h", "p", true, null)));

        agenda.delivered(loop(), List.of(scheduleFinding("hourly")));

        verify(store)
                .save(eq("acme"), eq(HOME), argThat(s -> s.enabled() && s.due().isAfter(Instant.now())));
    }

    @Test
    void refreshScheduleMarker_writesTheEarliestEnabledDue() {
        Instant early = Instant.parse("2026-10-09T07:00:00Z");
        when(store.list("acme", HOME))
                .thenReturn(List.of(
                        schedule("late", Instant.parse("2026-10-09T09:00:00Z"), true),
                        schedule("early", early, true),
                        schedule("parked", Instant.parse("2026-10-08T00:00:00Z"), false)));

        agenda.refreshScheduleMarker(loop());

        verify(wakeupService).setScheduleMarker("loop-1", early);
    }

    @Test
    void refreshScheduleMarker_recordsNothingScheduled() {
        when(store.list("acme", HOME)).thenReturn(List.of());

        agenda.refreshScheduleMarker(loop());

        verify(wakeupService).setScheduleMarker("loop-1", null);
    }

    private static ThinkProcessDocument loop() {
        ThinkProcessDocument loop = new ThinkProcessDocument();
        loop.setId("loop-1");
        loop.setTenantId("acme");
        loop.getEngineParams().put(TrillianSessionBootstrapper.PARAM_TRILLIAN_USER_NAME, ACCOUNT);
        return loop;
    }

    private static TrillianScheduleStore.Schedule schedule(String name, Instant due, boolean enabled) {
        return new TrillianScheduleStore.Schedule(name, null, due, null, "payload " + name, enabled, null);
    }

    private static SelfCheckFinding scheduleFinding(String name) {
        return new SelfCheckFinding(SelfCheckFinding.Kind.SCHEDULE_DUE, name, name, "x");
    }
}
