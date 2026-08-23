package de.mhus.vance.brain.jaglan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.sourceconfig.SourceConfig;
import de.mhus.vance.brain.sourceconfig.SourceConfigLoader;
import de.mhus.vance.brain.sourceconfig.SourceConfigPaths;
import de.mhus.vance.toolpack.core.SecretResolver;
import de.mhus.vance.toolpack.jaglan.JaglanInstance;
import de.mhus.vance.toolpack.jaglan.JaglanInstanceConfig;
import de.mhus.vance.toolpack.jaglan.JaglanProtocol;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Configuration documents → mount instances. The behaviour that matters is what
 * happens to a <i>broken</i> mount: it is dropped with a log line, never fatal,
 * because one misconfigured mount must not take a project's other mounts down
 * with it.
 */
class JaglanSourceFactoryTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "research";

    private SourceConfigLoader configLoader;
    private JaglanProtocol protocol;
    private JaglanSourceFactory factory;

    @BeforeEach
    void setUp() {
        configLoader = mock(SourceConfigLoader.class);
        protocol = mock(JaglanProtocol.class);
        JaglanProtocol local = protocol;
        when(local.id()).thenReturn("local");
        when(local.instantiate(any(JaglanInstanceConfig.class))).thenAnswer(inv -> {
            JaglanInstanceConfig cfg = inv.getArgument(0);
            JaglanInstance instance = mock(JaglanInstance.class);
            when(instance.mount()).thenReturn(cfg.mount());
            when(instance.protocolId()).thenReturn(cfg.protocolId());
            return instance;
        });
        factory = new JaglanSourceFactory(
                configLoader, SecretResolver.PASSTHROUGH, List.of(local));
    }

    private void given(SourceConfig... configs) {
        when(configLoader.load(eq(TENANT), eq(PROJECT), eq(SourceConfigPaths.MOUNTS)))
                .thenReturn(List.of(configs));
    }

    /** A mount document as the loader would hand it over. */
    private static SourceConfig mount(String name, String protocol, Object... extraPairs) {
        Map<String, Object> extras = new LinkedHashMap<>();
        for (int i = 0; i < extraPairs.length; i += 2) {
            extras.put(String.valueOf(extraPairs[i]), extraPairs[i + 1]);
        }
        boolean enabled = !Boolean.FALSE.equals(extras.remove("enabled"));
        return new SourceConfig(
                name, SourceConfigPaths.pathFor(SourceConfigPaths.MOUNTS, name),
                protocol, (String) extras.remove("baseUrl"), (String) extras.remove("apiKey"),
                enabled, extras);
    }

    @Test
    void assemble_buildsOneInstancePerConfiguredMount() {
        given(mount("library", "local", "rootDir", "/srv/books"),
                mount("archive", "local", "rootDir", "/srv/archive"));

        assertThat(factory.assemble(TENANT, PROJECT))
                .extracting(JaglanInstance::mount)
                .containsExactlyInAnyOrder("library", "archive");
    }

    @Test
    void assemble_noDocuments_isEmpty() {
        given();

        assertThat(factory.assemble(TENANT, PROJECT)).isEmpty();
    }

    @Test
    void assemble_mountWithoutProtocol_isSkipped() {
        given(mount("library", null, "rootDir", "/srv/books"));

        assertThat(factory.assemble(TENANT, PROJECT)).isEmpty();
    }

    @Test
    void assemble_unknownProtocol_isSkippedNotFatal() {
        given(mount("library", "does-not-exist"),
                mount("archive", "local", "rootDir", "/srv/archive"));

        assertThat(factory.assemble(TENANT, PROJECT))
                .extracting(JaglanInstance::mount).containsExactly("archive");
    }

    @Test
    void assemble_illegalMountName_isSkipped() {
        // The name becomes a path segment and part of every derived document
        // id, so it has to be refused where a human can fix it.
        given(mount("Bad Name", "local", "rootDir", "/srv/x"),
                mount("good", "local", "rootDir", "/srv/y"));

        assertThat(factory.assemble(TENANT, PROJECT))
                .extracting(JaglanInstance::mount).containsExactly("good");
    }

    @Test
    void assemble_disabledMount_isSkipped() {
        given(mount("library", "local", "rootDir", "/srv/books", "enabled", false));

        assertThat(factory.assemble(TENANT, PROJECT)).isEmpty();
    }

    @Test
    void assemble_protocolRefusal_dropsOnlyThatMount() {
        JaglanProtocol picky = mock(JaglanProtocol.class);
        when(picky.id()).thenReturn("picky");
        when(picky.instantiate(any())).thenThrow(new IllegalArgumentException("rootDir required"));
        JaglanProtocol local = mock(JaglanProtocol.class);
        when(local.id()).thenReturn("local");
        when(local.instantiate(any())).thenAnswer(inv -> {
            JaglanInstance i = mock(JaglanInstance.class);
            when(i.mount()).thenReturn(((JaglanInstanceConfig) inv.getArgument(0)).mount());
            return i;
        });
        factory = new JaglanSourceFactory(
                configLoader, SecretResolver.PASSTHROUGH, List.of(picky, local));
        given(mount("broken", "picky"), mount("fine", "local"));

        assertThat(factory.assemble(TENANT, PROJECT))
                .extracting(JaglanInstance::mount).containsExactly("fine");
    }

    @Test
    void extrasCarryEverythingButTheCommonFields() {
        given(mount("library", "local",
                "baseUrl", "https://example.test",
                "apiKey", "{noop}secret",
                "rootDir", "/srv/books",
                "writable", true));

        ArgumentCaptor<JaglanInstanceConfig> captor =
                ArgumentCaptor.forClass(JaglanInstanceConfig.class);
        factory.assemble(TENANT, PROJECT);
        verify(protocol).instantiate(captor.capture());
        JaglanInstanceConfig cfg = captor.getValue();

        assertThat(cfg.extras()).containsOnlyKeys("rootDir", "writable");
        assertThat(cfg.baseUrl()).isEqualTo("https://example.test");
        assertThat(cfg.credentials().get()).isEqualTo("secret");
        assertThat(cfg.credentialSettingKey())
                .isEqualTo("_vance/config/mounts/library.yaml#apiKey");
    }

    @Test
    void find_returnsNullForAnUnconfiguredMount() {
        given(mount("library", "local", "rootDir", "/srv/books"));

        assertThat(factory.find(TENANT, PROJECT, "library")).isNotNull();
        assertThat(factory.find(TENANT, PROJECT, "nope")).isNull();
    }

    @Test
    void assemble_blankScope_isEmptyWithoutReadingAnything() {
        assertThat(factory.assemble("", PROJECT)).isEmpty();
        assertThat(factory.assemble(TENANT, "")).isEmpty();
    }
}
