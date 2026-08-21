package de.mhus.vance.brain.jaglan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.documents.MountAccess;
import de.mhus.vance.api.mount.MountedSource;
import de.mhus.vance.api.mount.MountedStat;
import de.mhus.vance.shared.document.jaglan.JaglanAccessException;
import de.mhus.vance.shared.document.jaglan.JaglanUnavailableException;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.toolpack.jaglan.JaglanCapabilities;
import de.mhus.vance.toolpack.jaglan.JaglanInstance;
import de.mhus.vance.toolpack.jaglan.JaglanProtocolException;
import io.micrometer.core.instrument.Counter;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What the dispatcher itself is responsible for: finding the instance behind a
 * mount name, keeping {@code mounts()} off the network, and turning protocol
 * failures into the two answers the document layer can act on.
 */
class JaglanServiceTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "research";
    private static final String MOUNT = "library";

    private JaglanSourceFactory factory;
    private JaglanCapabilitiesCache capabilities;
    private JaglanInstance instance;
    private JaglanService service;

    @BeforeEach
    void setUp() {
        factory = mock(JaglanSourceFactory.class);
        capabilities = mock(JaglanCapabilitiesCache.class);
        MetricService metrics = mock(MetricService.class);
        when(metrics.counter(anyString(), anyString(), anyString()))
                .thenReturn(mock(Counter.class));
        instance = mock(JaglanInstance.class);
        when(instance.mount()).thenReturn(MOUNT);
        when(instance.protocolId()).thenReturn("local");
        service = new JaglanService(factory, capabilities, metrics);
    }

    private void configured() {
        when(factory.assemble(TENANT, PROJECT)).thenReturn(List.of(instance));
        when(factory.find(TENANT, PROJECT, MOUNT)).thenReturn(instance);
    }

    private static JaglanCapabilities caps(MountAccess access) {
        return new JaglanCapabilities(
                access, false, 42L, Duration.ofMinutes(3), null, "Book Library");
    }

    // ─── mounts() stays off the network ─────────────────────────────────

    @Test
    void mounts_readsTheCacheAndNeverFetches() {
        configured();
        when(capabilities.peek(TENANT, PROJECT, MOUNT)).thenReturn(caps(MountAccess.RO));

        List<MountedSource> mounts = service.mounts(TENANT, PROJECT);

        assertThat(mounts).hasSize(1);
        assertThat(mounts.get(0).access()).isEqualTo(MountAccess.RO);
        assertThat(mounts.get(0).itemCount()).isEqualTo(42L);
        assertThat(mounts.get(0).metadataTtl()).isEqualTo(Duration.ofMinutes(3));
        // Folder listings call this; a fetch here means a dead mount costs a
        // timeout before the tree renders.
        verify(capabilities, never()).warm(anyString(), anyString(), org.mockito.ArgumentMatchers.any());
        verify(instance, never()).capabilities();
    }

    @Test
    void mounts_coldCache_reportsUnknownRatherThanHiding() {
        configured();
        when(capabilities.peek(TENANT, PROJECT, MOUNT)).thenReturn(null);

        List<MountedSource> mounts = service.mounts(TENANT, PROJECT);

        // "Not configured" and "not answering yet" are different facts; only
        // the first justifies absence from the tree.
        assertThat(mounts).hasSize(1);
        assertThat(mounts.get(0).access()).isEqualTo(MountAccess.UNKNOWN);
        assertThat(mounts.get(0).itemCount()).isNull();
        assertThat(mounts.get(0).statusText()).contains("capabilities not loaded");
    }

    @Test
    void mounts_noneConfigured_isEmpty() {
        when(factory.assemble(TENANT, PROJECT)).thenReturn(List.of());

        assertThat(service.mounts(TENANT, PROJECT)).isEmpty();
    }

    // ─── unknown mount ──────────────────────────────────────────────────

    @Test
    void stat_unknownMount_isARefusalNotAnOutage() {
        when(factory.find(TENANT, PROJECT, MOUNT)).thenReturn(null);

        // Our own answer, not the source's — and permanent until someone edits
        // settings, so it must not invite a retry.
        assertThatThrownBy(() -> service.stat(TENANT, PROJECT, MOUNT, "x.pdf"))
                .isInstanceOf(JaglanAccessException.class)
                .hasMessageContaining("no mount 'library' configured");
    }

    // ─── failure translation ────────────────────────────────────────────

    @Test
    void refusedProtocolFailure_becomesAnAccessException() {
        configured();
        when(instance.stat(anyString()))
                .thenThrow(new JaglanProtocolException(MOUNT, "read-only"));

        assertThatThrownBy(() -> service.stat(TENANT, PROJECT, MOUNT, "x.pdf"))
                .isInstanceOf(JaglanAccessException.class)
                .hasMessageContaining("read-only");
    }

    @Test
    void transientProtocolFailure_becomesAnUnavailableException() {
        configured();
        when(instance.list(anyString())).thenThrow(
                JaglanProtocolException.unavailable(MOUNT, "connect timeout", null));

        assertThatThrownBy(() -> service.list(TENANT, PROJECT, MOUNT, ""))
                .isInstanceOf(JaglanUnavailableException.class)
                .hasMessageContaining("connect timeout");
    }

    @Test
    void unmappedFailure_defaultsToTransient() {
        configured();
        when(instance.open(anyString())).thenThrow(new IllegalStateException("boom"));

        // The safer default of the two: a refusal would delete shell rows and
        // tell a reader the file does not exist, while an outage keeps the last
        // answer and retries.
        assertThatThrownBy(() -> service.open(TENANT, PROJECT, MOUNT, "x.pdf"))
                .isInstanceOf(JaglanUnavailableException.class);
    }

    @Test
    void alreadyTranslatedFailures_passThroughUnwrapped() {
        configured();
        when(instance.stat(anyString()))
                .thenThrow(new JaglanAccessException(MOUNT, "already decided"));

        assertThatThrownBy(() -> service.stat(TENANT, PROJECT, MOUNT, "x.pdf"))
                .isInstanceOf(JaglanAccessException.class)
                .hasMessageContaining("already decided");
    }

    // ─── happy paths warm the capabilities ──────────────────────────────

    @Test
    void stat_warmsTheCapabilitiesBecauseARemoteCallIsHappeningAnyway() {
        configured();
        when(instance.stat("x.pdf")).thenReturn(Optional.of(new MountedStat(
                "x.pdf", false, 3, "application/pdf", "e", null, MountAccess.RO)));

        assertThat(service.stat(TENANT, PROJECT, MOUNT, "x.pdf")).isPresent();
        verify(capabilities).warm(TENANT, PROJECT, instance);
    }

    @Test
    void refresh_evictsBothTheInstanceAndItsDeclaration() {
        service.refresh(TENANT, PROJECT, MOUNT);

        verify(capabilities).evict(TENANT, PROJECT, MOUNT);
        verify(factory).evict(TENANT, PROJECT);
    }
}
