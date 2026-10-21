package de.mhus.vance.brain.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The shape and semantics of the per-tenant backup policy:
 *
 * <ul>
 *   <li>empty / blank YAML — default-allowed (the file simply does not exist on disk).</li>
 *   <li>enabled: true — admits everyone when users is empty.</li>
 *   <li>enabled: true, users: [...] — admits only those accounts.</li>
 *   <li>enabled: false — denies everyone, regardless of users.</li>
 *   <li>idleDays — clamped to the hard cap so a tenant cannot widen past the global max.</li>
 * </ul>
 *
 * <p>Fail-closed on malformed input — the service layer uses that to deny
 * rather than fall back to defaults.
 */
class BackupAccessConfigTest {

    @Test
    void emptyYaml_isDefaultAllowed() {
        BackupAccessConfig config = BackupAccessConfig.parse("");
        assertThat(config.enabled()).isTrue();
        assertThat(config.users()).isEmpty();
        assertThat(config.idleDays()).isEqualTo(90);
        assertThat(config.admits("anyone")).isTrue();
    }

    @Test
    void missingFile_isDefaultAllowed() {
        // The service layer is what reads the file; the parser sees null on
        // a missing body. Same answer as empty.
        BackupAccessConfig config = BackupAccessConfig.parse(null);
        assertThat(config.enabled()).isTrue();
        assertThat(config.admits("alice")).isTrue();
    }

    @Test
    void enabledFalse_deniesEveryone() {
        BackupAccessConfig config = BackupAccessConfig.parse("enabled: false\n");
        assertThat(config.admits("alice")).isFalse();
        assertThat(config.admits("bob")).isFalse();
        assertThat(config.admits(null)).isFalse();
    }

    @Test
    void explicitUsers_admitsOnlyListedAccounts() {
        BackupAccessConfig config = BackupAccessConfig.parse("users:\n  - alice\n  - bob\n");
        assertThat(config.enabled()).isTrue();
        assertThat(config.admits("alice")).isTrue();
        assertThat(config.admits("bob")).isTrue();
        assertThat(config.admits("eve")).isFalse();
        assertThat(config.admits(null)).isFalse();
    }

    @Test
    void enabledFalse_overrides_usersList() {
        // If a tenant tries to disable AND list users (mistake or
        // schizophrenic config), the master switch wins.
        BackupAccessConfig config = BackupAccessConfig.parse("enabled: false\nusers:\n  - alice\n");
        assertThat(config.admits("alice")).isFalse();
    }

    @Test
    void idleDaysCeiling_clampsToGlobalMax() {
        // A tenant can narrow but not widen. The global cap is the
        // IntegrationTokenController's fixed-end maximum (365).
        BackupAccessConfig wide = BackupAccessConfig.parse("idleDays: 1000\n");
        assertThat(wide.effectiveIdleDaysCeiling()).isEqualTo(BackupAccessConfig.MAX_IDLE_DAYS);

        BackupAccessConfig narrow = BackupAccessConfig.parse("idleDays: 30\n");
        assertThat(narrow.effectiveIdleDaysCeiling()).isEqualTo(30);
    }

    @Test
    void idleDaysZero_orNegative_clampsToOne() {
        // Defensive floor: a tenant that writes 0 (or negative) gets the
        // narrowest possible window, not an always-expired token.
        BackupAccessConfig config = BackupAccessConfig.parse("idleDays: 0\n");
        assertThat(config.effectiveIdleDaysCeiling()).isEqualTo(1);
    }

    @Test
    void malformedYaml_throws() {
        // Caller (the service) catches this and returns denyOnFailure().
        assertThatThrownBy(() -> BackupAccessConfig.parse("users: not-a-list\n"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be a list");

        assertThatThrownBy(() -> BackupAccessConfig.parse("idleDays: not-a-number\n"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be a number");
    }
}
