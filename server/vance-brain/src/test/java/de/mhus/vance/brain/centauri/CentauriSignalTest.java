package de.mhus.vance.brain.centauri;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.agrajag.AgrajagChecker;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.toolpack.feed.FeedCapabilities;
import de.mhus.vance.toolpack.feed.FeedException;
import de.mhus.vance.toolpack.feed.FeedReportReason;
import de.mhus.vance.toolpack.feed.FeedScope;
import de.mhus.vance.toolpack.feed.FeedSelectorKind;
import de.mhus.vance.toolpack.feed.FeedSelectorMode;
import de.mhus.vance.toolpack.feed.FeedSignal;
import de.mhus.vance.toolpack.feed.FeedSignalOutcome;
import de.mhus.vance.toolpack.feed.FeedSignalRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.json.JsonMapper;

/**
 * The back channel has three refusals and they are deliberately different
 * things. Confusing them is the failure this test exists for: „we did not send"
 * must not look like a verdict the source gave.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CentauriSignalTest {

    private static final FeedScope SCOPE = new FeedScope("acme", "news", null, "marvin");

    @Mock
    private FeedSourceFactory factory;
    @Mock
    private CentauriGateService gate;
    @Mock
    private FeedActorResolver actorResolver;
    @Mock
    private AgrajagChecker agrajagChecker;

    private FakeFeedSource source;
    private CentauriService service;

    @BeforeEach
    void setUp() {
        source = new FakeFeedSource("hrafnagud");
        service = new CentauriService(
                factory,
                new FeedCapabilitiesCache(),
                gate,
                actorResolver,
                new CentauriCursorCodec(JsonMapper.builder().build()),
                agrajagChecker,
                new MetricService(new SimpleMeterRegistry()));
        when(factory.find(any(), eq("hrafnagud"))).thenReturn(source);
        when(gate.check(any(), any())).thenReturn(Optional.empty());
        when(actorResolver.resolve(any(), any())).thenReturn(null);
    }

    @Test
    void sendSignal_acceptedByTheSource() {
        source.withCapabilities(accepting(FeedSignal.REPORT));

        FeedSignalOutcome outcome = service.sendSignal("hrafnagud", report(), SCOPE);

        assertThat(outcome).isEqualTo(FeedSignalOutcome.ACCEPTED);
    }

    @Test
    void sendSignal_undeclaredSignal_isUnsupportedWithoutCallingTheSource() {
        source.withCapabilities(accepting());

        FeedSignalOutcome outcome = service.sendSignal("hrafnagud", report(), SCOPE);

        assertThat(outcome).isEqualTo(FeedSignalOutcome.UNSUPPORTED);
        // The UI should not have offered it; this is the second line, not the gate.
        assertThat(source.signals()).isEmpty();
    }

    @Test
    void sendSignal_unknownSource_raisesRatherThanReturningAVerdict() {
        when(factory.find(any(), eq("nope"))).thenReturn(null);

        assertThatThrownBy(() -> service.sendSignal("nope", report(), SCOPE))
                .isInstanceOf(CentauriException.class)
                .hasMessageContaining("unknown feed source");
    }

    @Test
    void sendSignal_gatedSource_raisesAndSaysNothingWasSent() {
        source.withCapabilities(accepting(FeedSignal.REPORT));
        when(gate.check(any(), any()))
                .thenReturn(Optional.of(CentauriGateService.Blocked.COOLING_DOWN));

        // Our refusal, not the source's — so not an outcome.
        assertThatThrownBy(() -> service.sendSignal("hrafnagud", report(), SCOPE))
                .isInstanceOf(CentauriException.class)
                .hasMessageContaining("nothing was sent");
        assertThat(source.signals()).isEmpty();
    }

    @Test
    void sendSignal_transportFailure_isReportedToTheFailureTrackerAndRaises() {
        source.withCapabilities(accepting(FeedSignal.REPORT))
                .failingSignalWith(new FeedException("upstream 503"));

        assertThatThrownBy(() -> service.sendSignal("hrafnagud", report(), SCOPE))
                .isInstanceOf(CentauriException.class)
                .hasMessageContaining("503");
        // Same treatment as a failed fetch, so a dead source cools down.
        verify(agrajagChecker).handle(
                eq(CentauriSettings.cooldownSubject("hrafnagud")), any(), any());
    }

    @Test
    void sendSignal_carriesTheDerivedActor_notTheOneTheCallerPassed() {
        source.withCapabilities(accepting(FeedSignal.REPORT));
        when(actorResolver.resolve(any(), any()))
                .thenReturn(new de.mhus.vance.toolpack.feed.FeedActor("pseudo-7"));

        service.sendSignal("hrafnagud", report(), SCOPE);

        assertThat(source.signals()).singleElement()
                .satisfies(sent -> assertThat(sent.actor()).isNotNull()
                        .satisfies(a -> assertThat(a.pseudonym()).isEqualTo("pseudo-7")));
    }

    @Test
    void sendSignal_withoutProjectScope_isRefused() {
        assertThatThrownBy(() -> service.sendSignal(
                "hrafnagud", report(), new FeedScope("acme", "", null, null)))
                .isInstanceOf(CentauriException.class)
                .hasMessageContaining("project scope");
    }

    @Test
    void sendSignal_neverConsultsTheSourceForAGatedOne() {
        source.withCapabilities(accepting(FeedSignal.REPORT));
        when(gate.check(any(), any()))
                .thenReturn(Optional.of(CentauriGateService.Blocked.DISABLED));

        assertThatThrownBy(() -> service.sendSignal("hrafnagud", report(), SCOPE))
                .isInstanceOf(CentauriException.class);
        verify(agrajagChecker, never()).handle(any(), any(), any());
    }

    // ── helpers ──────────────────────────────────────────────────────

    private static FeedSignalRequest report() {
        return FeedSignalRequest.report("i1", FeedReportReason.WRONG_CATEGORY, "wrong desk", null);
    }

    private static FeedCapabilities accepting(FeedSignal... signals) {
        return new FeedCapabilities(
                FeedSelectorMode.ENUMERABLE, Set.of(FeedSelectorKind.CATEGORY),
                false, false, false, false, true, 40,
                Set.of(signals), false, Duration.ofMinutes(30));
    }
}
