package de.mhus.vance.brain.jaglan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.documents.MountAccess;
import de.mhus.vance.shared.document.jaglan.JaglanAccessException;
import de.mhus.vance.shared.document.jaglan.JaglanUnavailableException;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.toolpack.jaglan.JaglanCapabilities;
import de.mhus.vance.toolpack.jaglan.JaglanInstance;
import io.micrometer.core.instrument.Counter;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The capability gate in front of a parameterised read.
 *
 * <p>A query is only sent to a source that declared it serves one. The
 * alternative — send it and see — means a source that ignores unknown
 * parameters answers with the <em>unparameterised</em> document, and the
 * reader gets a plausible wrong answer instead of a refusal.
 */
class JaglanServiceQueryTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "research";
    private static final String MOUNT = "hrafnagud";

    private JaglanSourceFactory factory;
    private JaglanCapabilitiesCache capabilities;
    private JaglanInstance instance;
    private JaglanService service;

    @BeforeEach
    void setUp() {
        factory = mock(JaglanSourceFactory.class);
        capabilities = mock(JaglanCapabilitiesCache.class);
        instance = mock(JaglanInstance.class);
        MetricService metrics = mock(MetricService.class);
        when(metrics.counter(anyString(), any(String[].class))).thenReturn(mock(Counter.class));

        service = new JaglanService(factory, capabilities, metrics);
        when(factory.find(TENANT, PROJECT, MOUNT)).thenReturn(instance);
        when(instance.mount()).thenReturn(MOUNT);
    }

    @Test
    void open_withQuery_againstADeclaringMount_forwardsIt() {
        when(capabilities.warm(TENANT, PROJECT, instance)).thenReturn(caps(true));
        when(instance.open("analysis.yaml", "from=2026-01")).thenReturn(stream("chart:"));

        InputStream in = service.open(TENANT, PROJECT, MOUNT, "analysis.yaml", "from=2026-01");

        assertThat(read(in)).isEqualTo("chart:");
    }

    @Test
    void open_withQuery_againstAMountThatDeclaredNone_isRefusedWithoutAsking() {
        when(capabilities.warm(TENANT, PROJECT, instance)).thenReturn(caps(false));

        assertThatThrownBy(() ->
                service.open(TENANT, PROJECT, MOUNT, "analysis.yaml", "from=2026-01"))
                .isInstanceOf(JaglanAccessException.class)
                .hasMessageContaining(MOUNT);

        // Not asked at all: the source must not see a query it did not declare.
        verify(instance, never()).open(anyString(), anyString());
        verify(instance, never()).open(anyString());
    }

    @Test
    void open_withQuery_whenTheDeclarationIsUnknown_isUnavailableNotRefused() {
        // A cold or failing declaration is not a statement that the mount
        // serves no parameters. Saying "refused" there would be the worst
        // answer available right after a restart.
        when(capabilities.warm(TENANT, PROJECT, instance)).thenReturn(null);

        assertThatThrownBy(() ->
                service.open(TENANT, PROJECT, MOUNT, "analysis.yaml", "from=2026-01"))
                .isInstanceOf(JaglanUnavailableException.class);
    }

    @Test
    void open_withoutQuery_neverConsultsTheDeclaration() {
        // The plain read is the hot path; a capability fetch there would put a
        // remote call in front of every document open.
        when(instance.open("notes.md")).thenReturn(stream("plain"));

        assertThat(read(service.open(TENANT, PROJECT, MOUNT, "notes.md", null)))
                .isEqualTo("plain");
        verify(capabilities, never()).warm(anyString(), anyString(), any());
    }

    @Test
    void open_withBlankQuery_isAPlainRead() {
        when(instance.open("notes.md")).thenReturn(stream("plain"));

        assertThat(read(service.open(TENANT, PROJECT, MOUNT, "notes.md", "   ")))
                .isEqualTo("plain");
        verify(capabilities, never()).warm(anyString(), anyString(), any());
    }

    private static JaglanCapabilities caps(boolean supportsQuery) {
        return new JaglanCapabilities(
                MountAccess.RO, false, null, Duration.ofMinutes(3), null, supportsQuery, "H");
    }

    private static InputStream stream(String body) {
        return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(InputStream in) {
        try (InputStream open = in) {
            return new String(open.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
