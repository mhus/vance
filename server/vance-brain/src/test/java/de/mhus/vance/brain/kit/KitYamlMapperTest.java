package de.mhus.vance.brain.kit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.kit.KitDescriptorDto;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@code kit.yaml} parsing rules around the
 * visibility flags introduced in {@code kits.md} §3.2: {@code artifact},
 * {@code installable}, {@code sealed}.
 */
class KitYamlMapperTest {

    @Test
    void parseDescriptor_omittedFlags_useDefaults() {
        String yaml = """
                name: my-kit
                description: a kit
                """;

        KitDescriptorDto parsed = KitYamlMapper.parseDescriptor(yaml);

        assertThat(parsed.isArtifact()).isFalse();
        assertThat(parsed.isInstallable()).isTrue();
        assertThat(parsed.isSealed()).isFalse();
    }

    @Test
    void parseDescriptor_artifactTrue_isReadBack() {
        String yaml = """
                name: tuning-kit
                description: extra tools
                artifact: true
                """;

        KitDescriptorDto parsed = KitYamlMapper.parseDescriptor(yaml);

        assertThat(parsed.isArtifact()).isTrue();
        assertThat(parsed.isInstallable()).isTrue();
        assertThat(parsed.isSealed()).isFalse();
    }

    @Test
    void parseDescriptor_installableFalse_isReadBack() {
        String yaml = """
                name: base-kit
                description: only via inherits
                installable: false
                """;

        KitDescriptorDto parsed = KitYamlMapper.parseDescriptor(yaml);

        assertThat(parsed.isInstallable()).isFalse();
    }

    @Test
    void parseDescriptor_sealedTrue_isReadBack() {
        String yaml = """
                name: customer-kit
                description: end product
                sealed: true
                """;

        KitDescriptorDto parsed = KitYamlMapper.parseDescriptor(yaml);

        assertThat(parsed.isSealed()).isTrue();
    }

    @Test
    void parseDescriptor_installableFalseAndSealed_rejected() {
        String yaml = """
                name: useless-kit
                description: cannot be used at all
                installable: false
                sealed: true
                """;

        assertThatThrownBy(() -> KitYamlMapper.parseDescriptor(yaml))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("'installable: false' and 'sealed: true'");
    }

    @Test
    void writeDescriptor_omitsFlagsAtDefault() {
        KitDescriptorDto descriptor = KitDescriptorDto.builder()
                .name("plain-kit")
                .description("nothing special")
                .build();

        String yaml = KitYamlMapper.writeDescriptor(descriptor);

        assertThat(yaml).doesNotContain("artifact");
        assertThat(yaml).doesNotContain("installable");
        assertThat(yaml).doesNotContain("sealed");
    }

    @Test
    void writeDescriptor_emitsFlagsWhenNonDefault() {
        KitDescriptorDto descriptor = KitDescriptorDto.builder()
                .name("flagged-kit")
                .description("all the flags")
                .artifact(true)
                .installable(false)
                .sealed(false) // sealed=true together with installable=false would be invalid
                .build();

        String yaml = KitYamlMapper.writeDescriptor(descriptor);

        assertThat(yaml).contains("artifact: true");
        assertThat(yaml).contains("installable: false");
        assertThat(yaml).doesNotContain("sealed:");
    }

    @Test
    void writeDescriptor_roundTripsThroughParse() {
        KitDescriptorDto original = KitDescriptorDto.builder()
                .name("rt-kit")
                .description("round trip")
                .artifact(true)
                .build();

        KitDescriptorDto parsed =
                KitYamlMapper.parseDescriptor(KitYamlMapper.writeDescriptor(original));

        assertThat(parsed.isArtifact()).isTrue();
        assertThat(parsed.isInstallable()).isTrue();
        assertThat(parsed.isSealed()).isFalse();
    }
}
