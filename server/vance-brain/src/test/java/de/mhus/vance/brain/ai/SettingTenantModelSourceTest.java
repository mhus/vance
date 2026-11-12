package de.mhus.vance.brain.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.settings.SettingService;
import org.junit.jupiter.api.Test;

class SettingTenantModelSourceTest {

    private final SettingService settings = mock(SettingService.class);
    private final SettingTenantModelSource source = new SettingTenantModelSource(settings);

    private void stub(String tenant, String value) {
        when(settings.forceGetStringValue(
                        eq(tenant),
                        eq(SettingService.SCOPE_PROJECT),
                        eq(HomeBootstrapService.TENANT_PROJECT_NAME),
                        eq(SettingTenantModelSource.KEY)))
                .thenReturn(value);
    }

    @Test
    void bundledMeansClasspath_otherwiseCentral() {
        stub("_vance", "bundled");
        stub("acme", "central");

        assertThat(source.usesClasspathCatalog("_vance")).isTrue();
        assertThat(source.usesClasspathCatalog("acme")).isFalse();
        assertThat(source.usesClasspathCatalog("other")).isFalse();
    }

    @Test
    void theValueIsCaseAndWhitespaceTolerant() {
        stub("_vance", " Bundled ");

        assertThat(source.usesClasspathCatalog("_vance")).isTrue();
    }

    @Test
    void theAnswerIsCached() {
        stub("_vance", "bundled");

        source.usesClasspathCatalog("_vance");
        source.usesClasspathCatalog("_vance");

        verify(settings, times(1)).forceGetStringValue(any(), any(), any(), any());
    }

    @Test
    void anUnreadableStoreKeepsTheLastAnswerAndDefaultsToCentralAtFirst() {
        when(settings.forceGetStringValue(any(), any(), any(), any())).thenThrow(new IllegalStateException("down"));

        assertThat(source.usesClasspathCatalog("_vance")).isFalse();
    }
}
