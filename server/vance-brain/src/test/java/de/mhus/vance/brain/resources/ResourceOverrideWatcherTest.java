package de.mhus.vance.brain.resources;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.resourceoverride.ResourceOverrideRef;
import de.mhus.vance.shared.resourceoverride.ResourceOverrideService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ResourceOverrideWatcherTest {

    private static final String SECTION = "model";

    private ResourceOverrideService service;
    private BundledResourceResolver resolver;
    private ResourceOverrideConsumer consumer;
    private ResourceOverrideWatcher watcher;

    @BeforeEach
    void setUp() {
        service = mock(ResourceOverrideService.class);
        resolver = mock(BundledResourceResolver.class);
        consumer = mock(ResourceOverrideConsumer.class);
        when(consumer.section()).thenReturn(SECTION);
        when(resolver.isCentralManaged(SECTION)).thenReturn(true);
        watcher = new ResourceOverrideWatcher(service, resolver, List.of(consumer));
    }

    private void valid(long version, boolean overwrite) {
        when(service.effective(SECTION))
                .thenReturn(Optional.of(new ResourceOverrideRef(SECTION, version, overwrite, "d")));
    }

    @Test
    void poll_sameVersionAsLoaded_doesNotReload() {
        valid(4, true);
        when(resolver.appliedKey(SECTION)).thenReturn("4:true");

        watcher.poll();

        verify(consumer, never()).reload();
    }

    @Test
    void poll_newVersion_reloadsOnce() {
        valid(5, true);
        when(resolver.appliedKey(SECTION)).thenReturn("4:true");

        watcher.poll();

        verify(consumer, times(1)).reload();
    }

    @Test
    void poll_versionSwitchedOff_reloads() {
        valid(6, false);
        when(resolver.appliedKey(SECTION)).thenReturn("5:true");

        watcher.poll();

        verify(consumer).reload();
    }

    @Test
    void poll_nothingEnabledAndNothingLoadedYet_reloadsSoTheKeyIsRecorded() {
        when(service.effective(SECTION)).thenReturn(Optional.empty());
        when(resolver.appliedKey(SECTION)).thenReturn(null);

        watcher.poll();

        verify(consumer).reload();
    }

    @Test
    void poll_nothingEnabledAndAlreadyNone_doesNotReload() {
        when(service.effective(SECTION)).thenReturn(Optional.empty());
        when(resolver.appliedKey(SECTION)).thenReturn(BundledResourceResolver.NONE);

        watcher.poll();

        verify(consumer, never()).reload();
    }

    @Test
    void poll_storeFails_keepsTheStateAndDoesNotReload() {
        when(service.effective(SECTION)).thenThrow(new IllegalStateException("mongo down"));

        watcher.poll();

        verify(consumer, never()).reload();
    }

    @Test
    void poll_sectionNotCentrallyManaged_isSkipped() {
        when(resolver.isCentralManaged(SECTION)).thenReturn(false);

        watcher.poll();

        verify(service, never()).effective(SECTION);
    }

    @Test
    void poll_reloadThrows_doesNotStopTheOtherConsumers() {
        ResourceOverrideConsumer other = mock(ResourceOverrideConsumer.class);
        when(other.section()).thenReturn("recipes");
        when(resolver.isCentralManaged("recipes")).thenReturn(true);
        when(service.effective("recipes")).thenReturn(Optional.of(new ResourceOverrideRef("recipes", 1, true, "d")));
        valid(5, true);
        when(resolver.appliedKey(SECTION)).thenReturn("4:true");
        org.mockito.Mockito.doThrow(new IllegalStateException("boom"))
                .when(consumer)
                .reload();
        watcher = new ResourceOverrideWatcher(service, resolver, List.of(consumer, other));

        watcher.poll();

        verify(other).reload();
    }
}
