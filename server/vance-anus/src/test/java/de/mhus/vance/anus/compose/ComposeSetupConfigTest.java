package de.mhus.vance.anus.compose;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the agent-config applier — the fail-closed contract is the
 * whole point: unknown keys and type errors must name the key, and the
 * "absent means keep" rule is what makes re-runs idempotent.
 */
class ComposeSetupConfigTest {

    private final SecretGenerator secrets = new SecretGenerator();

    @Test
    void appliesKnownKebabCaseKeys() {
        ComposeSetupState s = new ComposeSetupState();
        Map<String, Object> yaml = new LinkedHashMap<>();
        yaml.put("face-port", 1234);
        yaml.put("language-name", "German");
        yaml.put("language-code", "de");
        yaml.put("fook-enabled", false);
        yaml.put("external-access", true);
        yaml.put("external-url", "https://vance.example.de");
        yaml.put("caddy-tls", false);

        ComposeSetupConfig.applyTo(yaml, s, secrets);

        assertThat(s.getFacePort()).isEqualTo(1234);
        assertThat(s.getLanguageName()).isEqualTo("German");
        assertThat(s.getLanguageCode()).isEqualTo("de");
        assertThat(s.isFookEnabled()).isFalse(); // state default is on — config wins
        assertThat(s.isExternalAccess()).isTrue();
        assertThat(s.getExternalUrl()).isEqualTo("https://vance.example.de");
        assertThat(s.isCaddyTls()).isFalse();
    }

    @Test
    void unknownKeyFailsNamingTheKey() {
        ComposeSetupState s = new ComposeSetupState();
        Map<String, Object> yaml = new LinkedHashMap<>();
        yaml.put("face-port", 1234);
        yaml.put("face-potr", 1234); // agent typo

        assertThatThrownBy(() -> ComposeSetupConfig.applyTo(yaml, s, secrets))
                .isInstanceOf(ComposeSetupConfig.ConfigValidationException.class)
                .hasMessageContaining("face-potr")
                .hasMessageContaining("unknown setting");
    }

    @Test
    void typeErrorsAreCollectedAllAtOnce() {
        ComposeSetupState s = new ComposeSetupState();
        Map<String, Object> yaml = new LinkedHashMap<>();
        yaml.put("face-port", "not-a-number");
        yaml.put("redis-enabled", "yes");

        assertThatThrownBy(() -> ComposeSetupConfig.applyTo(yaml, s, secrets))
                .isInstanceOf(ComposeSetupConfig.ConfigValidationException.class)
                .hasMessageContaining("face-port")
                .hasMessageContaining("redis-enabled");
    }

    @Test
    void externalAccessWithoutUrlFails() {
        ComposeSetupState s = new ComposeSetupState();
        Map<String, Object> yaml = new LinkedHashMap<>();
        yaml.put("external-access", true);

        assertThatThrownBy(() -> ComposeSetupConfig.applyTo(yaml, s, secrets))
                .isInstanceOf(ComposeSetupConfig.ConfigValidationException.class)
                .hasMessageContaining("external-url: required when external-access is true");
    }

    @Test
    void generateClearsSecretForFreshGeneration() {
        ComposeSetupState s = new ComposeSetupState();
        s.setEncryptionPassword("prefilled");
        Map<String, Object> yaml = new LinkedHashMap<>();
        yaml.put("encryption-password", "generate");

        ComposeSetupConfig.applyTo(yaml, s, secrets);

        // Blank, not "generate" — ensureSecrets() mints a fresh value from here.
        assertThat(s.getEncryptionPassword()).isEmpty();
    }

    @Test
    void absentKeysKeepPrefill() {
        ComposeSetupState s = new ComposeSetupState();
        s.setEncryptionPassword("prefilled");
        s.setFookEnabled(false);
        Map<String, Object> yaml = new LinkedHashMap<>();
        yaml.put("face-port", 1234);

        ComposeSetupConfig.applyTo(yaml, s, secrets);

        assertThat(s.getEncryptionPassword()).isEqualTo("prefilled");
        assertThat(s.isFookEnabled()).isFalse();
    }

    @Test
    void anusPasswordIsHashed_emptyStringDisablesGate() {
        ComposeSetupState hashed = new ComposeSetupState();
        Map<String, Object> yaml = new LinkedHashMap<>();
        yaml.put("anus-password", "top-secret");
        ComposeSetupConfig.applyTo(yaml, hashed, secrets);
        assertThat(hashed.getAnusPasswordHash()).startsWith("$2");
        assertThat(hashed.getAnusPasswordHash()).doesNotContain("top-secret");

        ComposeSetupState open = new ComposeSetupState();
        open.setAnusPasswordHash("$2a$prefilled");
        Map<String, Object> disable = new LinkedHashMap<>();
        disable.put("anus-password", "");
        ComposeSetupConfig.applyTo(disable, open, secrets);
        assertThat(open.getAnusPasswordHash()).isEmpty();
    }

    @Test
    void nullAnusPasswordKeepsPrefilledHash() {
        ComposeSetupState s = new ComposeSetupState();
        s.setAnusPasswordHash("$2a$prefilled");
        Map<String, Object> yaml = new LinkedHashMap<>();
        yaml.put("anus-password", null);

        ComposeSetupConfig.applyTo(yaml, s, secrets);

        assertThat(s.getAnusPasswordHash()).isEqualTo("$2a$prefilled");
    }
}
