package de.mhus.vance.brain.kit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.kit.KitSignaturePolicy;
import de.mhus.vance.api.kit.KitSourceDto;
import de.mhus.vance.api.kit.KitSourceType;
import de.mhus.vance.api.kit.KitSourcesDto;
import de.mhus.vance.shared.kit.KitException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Parsing of {@code _vance/config/kit-sources.yaml} — spec:
 * {@code planning/kit-shop.md} §5.1.
 */
class KitSourcesYamlTest {

    @Test
    void sources_fullEntry_parses() {
        KitSourcesDto parsed = KitYamlMapper.parseSources("""
                sources:
                  - id: vancetope-library
                    type: library
                    url: https://library.vancetope.com
                    signature: required
                    publicKeys:
                      vancetope-library-2026: |
                        -----BEGIN PUBLIC KEY-----
                        abc
                        -----END PUBLIC KEY-----
                  - id: house-kits
                    type: git
                    url: https://git.intern.example/kits.git
                    signature: off
                """);

        assertThat(parsed.getSources()).hasSize(2);
        KitSourceDto library = parsed.getSources().get(0);
        assertThat(library.getType()).isEqualTo(KitSourceType.LIBRARY);
        assertThat(library.getSignature()).isEqualTo(KitSignaturePolicy.REQUIRED);
        assertThat(library.getPublicKeys()).containsOnlyKeys("vancetope-library-2026");
        assertThat(library.getPublicKeys().get("vancetope-library-2026")).contains("BEGIN PUBLIC KEY");
        assertThat(parsed.getSources().get(1).getSignature()).isEqualTo(KitSignaturePolicy.OFF);
    }

    @Test
    void sources_omittedSignature_takesTheTypeDefault() {
        // A library that forgets to say is still required; a git repo that
        // forgets to say is still off. Getting this backwards would either
        // break every existing install or silently accept unsigned purchases.
        KitSourcesDto parsed = KitYamlMapper.parseSources("""
                sources:
                  - id: lib
                    type: library
                    url: https://library.example
                  - id: repo
                    type: git
                    url: https://git.example/kits.git
                """);
        assertThat(parsed.getSources().get(0).getSignature()).isEqualTo(KitSignaturePolicy.REQUIRED);
        assertThat(parsed.getSources().get(1).getSignature()).isEqualTo(KitSignaturePolicy.OFF);
    }

    @Test
    void sources_offAsBareWord_isNotReadAsBoolean() {
        // YAML 1.1 turns a bare `off` into false long before we see it.
        // The most obvious way to write the most common setting must work.
        assertThat(KitYamlMapper.parseSources("""
                sources:
                  - id: repo
                    type: git
                    url: https://git.example/kits.git
                    signature: off
                """).getSources().get(0).getSignature())
                .isEqualTo(KitSignaturePolicy.OFF);
    }

    @Test
    void sources_onAsBareWord_isRefusedRatherThanGuessed() {
        // `on` becomes true, and true says nothing about whether signatures
        // are merely checked or actually required — so it asks instead.
        assertThatThrownBy(() -> KitYamlMapper.parseSources("""
                sources:
                  - id: lib
                    type: library
                    url: https://library.example
                    signature: on
                """))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("required")
                .hasMessageContaining("warn");
    }

    @Test
    void sources_emptyDocument_yieldsNoSources() {
        assertThat(KitYamlMapper.parseSources("").getSources()).isEmpty();
    }

    @Test
    void sources_unknownType_isRejectedByName() {
        assertThatThrownBy(() -> KitYamlMapper.parseSources("""
                sources:
                  - id: x
                    type: ftp
                    url: ftp://example
                """))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("ftp")
                .hasMessageContaining("library");
    }

    @Test
    void sources_unknownSignaturePolicy_isRejectedByName() {
        assertThatThrownBy(() -> KitYamlMapper.parseSources("""
                sources:
                  - id: x
                    type: git
                    url: https://git.example
                    signature: maybe
                """))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("maybe");
    }

    @Test
    void sources_duplicateId_isRejected() {
        // Ids show up in logs and messages; two sources answering to the same
        // name makes every one of those ambiguous.
        assertThatThrownBy(() -> KitYamlMapper.parseSources("""
                sources:
                  - id: dup
                    type: git
                    url: https://a.example
                  - id: dup
                    type: git
                    url: https://b.example
                """))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("dup");
    }

    @Test
    void sources_missingUrl_isRejected() {
        assertThatThrownBy(() -> KitYamlMapper.parseSources("""
                sources:
                  - id: x
                    type: git
                """))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("url");
    }

    @Test
    void sources_legacySinglePublicKey_isRefusedWithTheReplacement() {
        // The single key had no id, and a ring cannot pick a key without
        // one. Reading it as "trust under any id" would be a guess, and
        // dropping it silently would turn a required signature into a
        // NO_KEY failure nobody can explain.
        assertThatThrownBy(() -> KitYamlMapper.parseSources("""
                sources:
                  - id: lib
                    type: library
                    url: https://library.example
                    publicKey: |
                      -----BEGIN PUBLIC KEY-----
                      abc
                      -----END PUBLIC KEY-----
                """))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("publicKeys");
    }

    @Test
    void sources_emptyKeyRing_isRefused() {
        // Empty would read as "no keys" and fall back to the bundled ones
        // for the default library — not what someone writing an empty
        // ring meant, whatever they meant.
        assertThatThrownBy(() -> KitYamlMapper.parseSources("""
                sources:
                  - id: lib
                    type: library
                    url: https://library.example
                    publicKeys: {}
                """))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("publicKeys");
    }

    @Test
    void sources_roundTrip() {
        KitSourcesDto original = KitSourcesDto.builder()
                .sources(List.of(KitSourceDto.builder()
                        .id("lib")
                        .type(KitSourceType.LIBRARY)
                        .url("https://library.example")
                        .signature(KitSignaturePolicy.WARN)
                        .publicKeys(Map.of("lib-2026", "-----BEGIN PUBLIC KEY-----\nabc\n-----END PUBLIC KEY-----\n"))
                        .build()))
                .build();
        KitSourcesDto parsed = KitYamlMapper.parseSources(KitYamlMapper.writeSources(original));
        assertThat(parsed.getSources()).hasSize(1);
        assertThat(parsed.getSources().get(0).getSignature()).isEqualTo(KitSignaturePolicy.WARN);
        assertThat(parsed.getSources().get(0).getType()).isEqualTo(KitSourceType.LIBRARY);
        assertThat(parsed.getSources().get(0).getPublicKeys())
                .isEqualTo(original.getSources().get(0).getPublicKeys());
    }
}
