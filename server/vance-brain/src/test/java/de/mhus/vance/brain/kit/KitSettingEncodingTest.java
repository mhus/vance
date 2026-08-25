package de.mhus.vance.brain.kit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.kit.KitSecretEncoding;
import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.shared.kit.KitException;
import org.junit.jupiter.api.Test;

/**
 * The {@code encoding:} line of {@code settings/<key>.yaml} — how a kit says
 * whether its credential is a vault blob or the credential itself.
 *
 * <p>See {@code specification/public/kits.md} §9.
 */
class KitSettingEncodingTest {

    @Test
    void absentEncoding_meansVault() {
        // The compatibility guarantee: every kit written before the field
        // existed keeps needing a vault password, and none of them changed
        // meaning by this field being added.
        KitYamlMapper.ParsedSetting parsed = KitYamlMapper.parseSetting("""
                type: PASSWORD
                value: "<blob>"
                """, "api_token.yaml");

        assertThat(parsed.encoding()).isEqualTo(KitSecretEncoding.VAULT);
    }

    @Test
    void plainEncoding_isParsed() {
        KitYamlMapper.ParsedSetting parsed = KitYamlMapper.parseSetting("""
                type: PASSWORD
                encoding: plain
                value: "sk-live-abc"
                """, "api_token.yaml");

        assertThat(parsed.encoding()).isEqualTo(KitSecretEncoding.PLAIN);
        assertThat(parsed.value()).isEqualTo("sk-live-abc");
    }

    @Test
    void unknownEncoding_isRefusedRatherThanTakenAsVault() {
        // A typo in a security-relevant field. Falling back to the default
        // would turn `plian` into a decryption failure several layers away,
        // with a message about a vault blob and no hint at the real cause.
        assertThatThrownBy(() -> KitYamlMapper.parseSetting("""
                type: PASSWORD
                encoding: plian
                value: "sk-live-abc"
                """, "api_token.yaml"))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("unknown setting encoding 'plian'")
                .hasMessageContaining("vault, plain");
    }

    @Test
    void encodingOnAnUnencryptedType_isRefused() {
        // Nothing to encode, so writing the line means the author believed
        // something about this file that is not true — most likely that it
        // would be encrypted. Ignoring it would leave that belief intact.
        assertThatThrownBy(() -> KitYamlMapper.parseSetting("""
                type: STRING
                encoding: plain
                value: hello
                """, "greeting.yaml"))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("only meaningful for an encrypted setting type");
    }

    @Test
    void writeSetting_omitsTheDefault() {
        // So an export keeps producing byte-for-byte the file it always did.
        String yaml = KitYamlMapper.writeSetting(new KitYamlMapper.ParsedSetting(
                SettingType.PASSWORD, "<blob>", "a token"));

        assertThat(yaml).doesNotContain("encoding");
    }

    @Test
    void writeSetting_roundTripsPlain() {
        String yaml = KitYamlMapper.writeSetting(new KitYamlMapper.ParsedSetting(
                SettingType.PASSWORD, "sk-live-abc", "a token", KitSecretEncoding.PLAIN));

        assertThat(KitYamlMapper.parseSetting(yaml, "api_token.yaml").encoding())
                .isEqualTo(KitSecretEncoding.PLAIN);
    }
}
