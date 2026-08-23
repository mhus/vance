package de.mhus.vance.brain.kit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Which paths a kit may not ship by path alone. Only the kit subsystem's own
 * files qualify — anything that needs the content to decide is checked in
 * {@code KitInstaller} instead (see the {@code local} mount guard there).
 */
class KitRecordStoreReservedPathTest {

    @Test
    void kitSubsystemPathsAreReserved() {
        assertThat(KitRecordStore.isReservedPath("_vance/kits/installed/a.yaml")).isTrue();
        assertThat(KitRecordStore.isReservedPath("_vance/config/kit-sources.yaml")).isTrue();
    }

    @Test
    void sourceConfigurationStaysOpen_becauseThatIsWhatAKitIsFor() {
        // A feed source, a search endpoint and a remote mount are all connectors
        // an archive legitimately ships. Only the `local` mount protocol is
        // refused, and that check needs the content — see KitInstaller.
        assertThat(KitRecordStore.isReservedPath("_vance/config/feeds/hrafnagud.yaml")).isFalse();
        assertThat(KitRecordStore.isReservedPath("_vance/config/research/hrafnagud.yaml")).isFalse();
        assertThat(KitRecordStore.isReservedPath("_vance/config/mounts/hrafnagud.yaml")).isFalse();
        assertThat(KitRecordStore.isReservedPath("documents/notes.md")).isFalse();
    }
}
