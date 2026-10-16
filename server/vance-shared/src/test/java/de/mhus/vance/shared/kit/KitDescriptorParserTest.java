package de.mhus.vance.shared.kit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.kit.KitDescriptorDto;
import org.junit.jupiter.api.Test;

/**
 * The {@code kit.yaml} grammar both the brain and the store read — the
 * full field coverage lives with the brain's {@code KitYamlMapper} tests,
 * which go through here. These pin what the store relies on: that what
 * it accepts, a brain can read.
 */
class KitDescriptorParserTest {

    @Test
    void aMinimalDescriptor_parses() {
        KitDescriptorDto descriptor = KitDescriptorParser.parse(
                "name: store-smoke\ndescription: \"Smoke test: purchase, delivery\"\nversion: \"1.0.0\"\n");

        assertThat(descriptor.getName()).isEqualTo("store-smoke");
        assertThat(descriptor.getDescription()).isEqualTo("Smoke test: purchase, delivery");
        assertThat(descriptor.getVersion()).isEqualTo("1.0.0");
        assertThat(descriptor.isInstallable()).isTrue();
    }

    @Test
    void anUnquotedColonInAValue_isAParseError() {
        assertThatThrownBy(() ->
                        KitDescriptorParser.parse("name: store-smoke\ndescription: Smoke test: purchase, delivery\n"))
                .isInstanceOf(KitException.class)
                .hasMessageStartingWith("Failed to parse kit.yaml");
    }

    @Test
    void aMissingRequiredField_isNamed() {
        assertThatThrownBy(() -> KitDescriptorParser.parse("name: store-smoke\n"))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("'description'");
    }

    @Test
    void aTopLevelList_isRefused() {
        assertThatThrownBy(() -> KitDescriptorParser.parse("- name: store-smoke\n"))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("top-level map");
    }
}
