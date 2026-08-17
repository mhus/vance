package de.mhus.vance.brain.kit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Path layout and the write guard around {@code _vance/kits/} — spec:
 * {@code planning/kit-installed-multi.md} §2, §D8.
 */
class KitRecordStorePathTest {

    @Test
    void recordAndConfig_sameIdLandsInSiblingDirectories() {
        // Same id on both sides is what makes the pair greppable.
        assertThat(KitRecordStore.recordPath("kernel-security-a4f32b"))
                .isEqualTo("_vance/kits/installed/kernel-security-a4f32b.yaml");
        assertThat(KitRecordStore.configPath("kernel-security-a4f32b"))
                .isEqualTo("_vance/kits/config/kernel-security-a4f32b.yaml");
    }

    @Test
    void isReservedPath_installRecord_isReserved() {
        // A kit that could ship this would forge its own install record.
        assertThat(KitRecordStore.isReservedPath("_vance/kits/installed/evil-000000.yaml")).isTrue();
    }

    @Test
    void isReservedPath_configOfAnotherKit_isReserved() {
        // The nastier variant: not forging your own record but rewriting a
        // competing kit's policy to `ignore` so it stops updating.
        assertThat(KitRecordStore.isReservedPath("_vance/kits/config/other-abc123.yaml")).isTrue();
    }

    @Test
    void isReservedPath_leadingSlash_isStillReserved() {
        assertThat(KitRecordStore.isReservedPath("/_vance/kits/manifest.yaml")).isTrue();
    }

    @Test
    void isReservedPath_otherVanceDocuments_areAllowed() {
        // Kits are supposed to ship recipes, skills and server-tools —
        // the guard must be narrow enough to leave those alone.
        assertThat(KitRecordStore.isReservedPath("_vance/recipes/analyze.yaml")).isFalse();
        assertThat(KitRecordStore.isReservedPath("recipes/analyze.yaml")).isFalse();
        assertThat(KitRecordStore.isReservedPath("server-tools/grep.yaml")).isFalse();
    }

    @Test
    void isReservedPath_similarlyNamedDirectory_isAllowed() {
        assertThat(KitRecordStore.isReservedPath("_vance/kits-notes/readme.md")).isFalse();
    }
}
