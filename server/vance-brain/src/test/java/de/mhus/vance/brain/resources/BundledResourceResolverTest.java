package de.mhus.vance.brain.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.resourceoverride.ResourceOverrideDocument;
import de.mhus.vance.shared.resourceoverride.ResourceOverrideFile;
import de.mhus.vance.shared.resourceoverride.ResourceOverrideRef;
import de.mhus.vance.shared.resourceoverride.ResourceOverrideService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BundledResourceResolverTest {

    private static final String SECTION = "model";

    private ResourceOverrideService service;
    private BundledResourceResolver resolver;

    @BeforeEach
    void setUp() {
        service = mock(ResourceOverrideService.class);
        resolver = new BundledResourceResolver(service, true, List.of(SECTION));
    }

    private static ResourceOverrideDocument central(long version, String path) {
        return ResourceOverrideDocument.builder()
                .type(SECTION)
                .version(version)
                .enabled(true)
                .overwrite(true)
                .files(List.of(new ResourceOverrideFile(path, "x: 1")))
                .build();
    }

    @Test
    void featureOff_readsTheClasspathAndNeverTouchesTheStore() {
        BundledResourceResolver off = new BundledResourceResolver(service, false, List.of(SECTION));

        BundledResources res = off.load(SECTION);

        assertThat(res.origin()).isEqualTo(BundledResources.CLASSPATH);
        assertThat(res.files()).isNotEmpty();
        verify(service, never()).effective(SECTION);
    }

    @Test
    void sectionNotListed_readsTheClasspath() {
        BundledResourceResolver other = new BundledResourceResolver(service, true, List.of("recipes"));

        assertThat(other.load(SECTION).origin()).isEqualTo(BundledResources.CLASSPATH);
        verify(service, never()).effective(SECTION);
    }

    @Test
    void noEnabledVersion_readsTheClasspath() {
        when(service.effective(SECTION)).thenReturn(Optional.empty());

        assertThat(resolver.load(SECTION).origin()).isEqualTo(BundledResources.CLASSPATH);
        assertThat(resolver.appliedKey(SECTION)).isEqualTo(BundledResourceResolver.NONE);
    }

    @Test
    void validVersionWithOverwriteFalse_readsTheClasspath() {
        when(service.effective(SECTION)).thenReturn(Optional.of(new ResourceOverrideRef(SECTION, 3, false, "d")));

        assertThat(resolver.load(SECTION).origin()).isEqualTo(BundledResources.CLASSPATH);
        assertThat(resolver.appliedKey(SECTION)).isEqualTo("3:false");
    }

    @Test
    void validVersionWithOverwrite_returnsItsFiles() {
        when(service.effective(SECTION)).thenReturn(Optional.of(new ResourceOverrideRef(SECTION, 4, true, "d")));
        when(service.find(SECTION, 4)).thenReturn(Optional.of(central(4, "gw/m.yaml")));

        BundledResources res = resolver.load(SECTION);

        assertThat(res.isCentral()).isTrue();
        assertThat(res.origin()).isEqualTo("central v4");
        assertThat(res.files()).containsExactly(new BundledResourceFile("gw/m.yaml", "x: 1"));
        assertThat(resolver.appliedKey(SECTION)).isEqualTo("4:true");
    }

    @Test
    void storeUnreadableAtStart_fallsBackToTheClasspathWithoutAKey() {
        when(service.effective(SECTION)).thenThrow(new IllegalStateException("mongo down"));

        assertThat(resolver.load(SECTION).origin()).isEqualTo(BundledResources.CLASSPATH);
        assertThat(resolver.appliedKey(SECTION)).isNull();
    }

    @Test
    void storeUnreadableLater_keepsTheLastCentralResult() {
        when(service.effective(SECTION)).thenReturn(Optional.of(new ResourceOverrideRef(SECTION, 4, true, "d")));
        when(service.find(SECTION, 4)).thenReturn(Optional.of(central(4, "gw/m.yaml")));
        resolver.load(SECTION);
        when(service.effective(SECTION)).thenThrow(new IllegalStateException("mongo down"));

        BundledResources res = resolver.load(SECTION);

        assertThat(res.origin()).isEqualTo("central v4");
        assertThat(resolver.appliedKey(SECTION)).isEqualTo("4:true");
    }

    @Test
    void versionVanishedWhileLoading_keepsTheLastResult() {
        when(service.effective(SECTION)).thenReturn(Optional.of(new ResourceOverrideRef(SECTION, 4, true, "d")));
        when(service.find(SECTION, 4)).thenReturn(Optional.of(central(4, "gw/m.yaml")));
        resolver.load(SECTION);
        when(service.effective(SECTION)).thenReturn(Optional.of(new ResourceOverrideRef(SECTION, 5, true, "e")));
        when(service.find(SECTION, 5)).thenReturn(Optional.empty());

        assertThat(resolver.load(SECTION).origin()).isEqualTo("central v4");
    }

    @Test
    void centralSections_isEmptyWhenTheFeatureIsOff() {
        assertThat(new BundledResourceResolver(service, false, List.of(SECTION)).centralSections())
                .isEmpty();
        assertThat(resolver.centralSections()).containsExactly(SECTION);
    }
}
