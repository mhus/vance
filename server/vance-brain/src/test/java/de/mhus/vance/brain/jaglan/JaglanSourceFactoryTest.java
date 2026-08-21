package de.mhus.vance.brain.jaglan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.toolpack.jaglan.JaglanInstance;
import de.mhus.vance.toolpack.jaglan.JaglanInstanceConfig;
import de.mhus.vance.toolpack.jaglan.JaglanProtocol;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Settings → mount instances. The behaviour that matters is what happens to a
 * <i>broken</i> mount: it is dropped with a log line, never fatal, because one
 * misconfigured mount must not take a project's other mounts down with it.
 */
class JaglanSourceFactoryTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "research";

    private SettingService settings;
    private JaglanSourceFactory factory;

    @BeforeEach
    void setUp() {
        settings = mock(SettingService.class);
        JaglanProtocol local = mock(JaglanProtocol.class);
        when(local.id()).thenReturn("local");
        when(local.instantiate(any(JaglanInstanceConfig.class))).thenAnswer(inv -> {
            JaglanInstanceConfig cfg = inv.getArgument(0);
            JaglanInstance instance = mock(JaglanInstance.class);
            when(instance.mount()).thenReturn(cfg.mount());
            when(instance.protocolId()).thenReturn(cfg.protocolId());
            return instance;
        });
        factory = new JaglanSourceFactory(settings, List.of(local));
    }

    private void given(Map<String, String> raw) {
        when(settings.findByPrefixCascade(eq(TENANT), eq(PROJECT), isNull(), anyString()))
                .thenReturn(raw);
    }

    private static Map<String, String> keys(String... pairs) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put(pairs[i], pairs[i + 1]);
        }
        return out;
    }

    @Test
    void assemble_buildsOneInstancePerConfiguredMount() {
        given(keys(
                "jaglan.mount.library.protocol", "local",
                "jaglan.mount.library.rootDir", "/srv/books",
                "jaglan.mount.archive.protocol", "local",
                "jaglan.mount.archive.rootDir", "/srv/archive"));

        assertThat(factory.assemble(TENANT, PROJECT))
                .extracting(JaglanInstance::mount)
                .containsExactlyInAnyOrder("library", "archive");
    }

    @Test
    void assemble_noSettings_isEmpty() {
        given(Map.of());

        assertThat(factory.assemble(TENANT, PROJECT)).isEmpty();
    }

    @Test
    void assemble_mountWithoutProtocol_isSkipped() {
        // This is the hook a setting form uses to disable a mount without
        // deleting its other keys.
        given(keys("jaglan.mount.library.rootDir", "/srv/books"));

        assertThat(factory.assemble(TENANT, PROJECT)).isEmpty();
    }

    @Test
    void assemble_unknownProtocol_isSkippedNotFatal() {
        given(keys(
                "jaglan.mount.library.protocol", "does-not-exist",
                "jaglan.mount.archive.protocol", "local",
                "jaglan.mount.archive.rootDir", "/srv/archive"));

        assertThat(factory.assemble(TENANT, PROJECT))
                .extracting(JaglanInstance::mount).containsExactly("archive");
    }

    @Test
    void assemble_illegalMountName_isSkipped() {
        // The name becomes a path segment and part of every derived document
        // id, so it has to be refused where a human can fix it.
        given(keys(
                "jaglan.mount.Bad Name.protocol", "local",
                "jaglan.mount.Bad Name.rootDir", "/srv/x",
                "jaglan.mount.good.protocol", "local",
                "jaglan.mount.good.rootDir", "/srv/y"));

        assertThat(factory.assemble(TENANT, PROJECT))
                .extracting(JaglanInstance::mount).containsExactly("good");
    }

    @Test
    void assemble_disabledMount_isSkipped() {
        given(keys(
                "jaglan.mount.library.protocol", "local",
                "jaglan.mount.library.rootDir", "/srv/books",
                "jaglan.mount.library.enabled", "false"));

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
        factory = new JaglanSourceFactory(settings, List.of(picky, local));
        given(keys(
                "jaglan.mount.broken.protocol", "picky",
                "jaglan.mount.fine.protocol", "local"));

        assertThat(factory.assemble(TENANT, PROJECT))
                .extracting(JaglanInstance::mount).containsExactly("fine");
    }

    @Test
    void assemble_extrasCarryEverythingButTheFourCommonFields() {
        given(keys(
                "jaglan.mount.library.protocol", "local",
                "jaglan.mount.library.baseUrl", "https://example.test",
                "jaglan.mount.library.apiKey", "secret",
                "jaglan.mount.library.enabled", "true",
                "jaglan.mount.library.rootDir", "/srv/books",
                "jaglan.mount.library.writable", "true"));

        Map<String, Map<String, String>> grouped = JaglanSourceFactory.groupByMount(keys(
                "jaglan.mount.library.protocol", "local",
                "jaglan.mount.library.rootDir", "/srv/books"));
        assertThat(grouped).containsOnlyKeys("library");
        assertThat(grouped.get("library")).containsOnlyKeys("protocol", "rootDir");

        // And the four common fields are recognised as such.
        assertThat(JaglanSettings.isCommonField("protocol")).isTrue();
        assertThat(JaglanSettings.isCommonField("baseUrl")).isTrue();
        assertThat(JaglanSettings.isCommonField("apiKey")).isTrue();
        assertThat(JaglanSettings.isCommonField("enabled")).isTrue();
        assertThat(JaglanSettings.isCommonField("rootDir")).isFalse();
        assertThat(JaglanSettings.isCommonField("writable")).isFalse();
    }

    @Test
    void groupByMount_skipsKeysWithoutASuffix() {
        Map<String, Map<String, String>> grouped = JaglanSourceFactory.groupByMount(keys(
                "jaglan.mount.library", "nonsense",
                "jaglan.mount.library.protocol", "local",
                "unrelated.setting", "x"));

        assertThat(grouped).containsOnlyKeys("library");
        assertThat(grouped.get("library")).containsOnlyKeys("protocol");
    }

    @Test
    void find_returnsNullForAnUnconfiguredMount() {
        given(keys(
                "jaglan.mount.library.protocol", "local",
                "jaglan.mount.library.rootDir", "/srv/books"));

        assertThat(factory.find(TENANT, PROJECT, "library")).isNotNull();
        assertThat(factory.find(TENANT, PROJECT, "nope")).isNull();
    }

    @Test
    void assemble_blankScope_isEmptyWithoutTouchingSettings() {
        assertThat(factory.assemble("", PROJECT)).isEmpty();
        assertThat(factory.assemble(TENANT, "")).isEmpty();
    }
}
