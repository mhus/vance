package de.mhus.vance.brain.kit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.kit.KitDescriptorDto;
import de.mhus.vance.api.kit.KitSignaturePolicy;
import de.mhus.vance.api.kit.KitSourceDto;
import de.mhus.vance.api.kit.KitSourceType;
import de.mhus.vance.shared.kit.KitException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Policy enforcement per source — spec: {@code planning/kit-shop.md}
 * §4 E3.
 *
 * <p>Uses an unsigned kit throughout: the interesting axis here is what
 * each policy <em>does</em> with a failure, not which failure it was —
 * that is {@link KitSignatureTest}'s job.
 */
class KitSignatureGateTest {

    @TempDir
    Path tmp;

    private final KitSignatureGate gate = new KitSignatureGate();
    private Path kit;

    @BeforeEach
    void setUp() throws Exception {
        kit = tmp.resolve("kit");
        Files.createDirectories(kit);
        Files.writeString(kit.resolve("kit.yaml"), "name: plain\ndescription: unsigned\n");
    }

    @Test
    void off_unsignedKit_passes() {
        // The default for git and folder sources. Requiring signatures
        // there would break every existing installation.
        assertThatCode(() -> gate.enforce(kit, descriptor(), source(KitSignaturePolicy.OFF)))
                .doesNotThrowAnyException();
    }

    @Test
    void warn_unsignedKit_passes() {
        // For a source being migrated to signatures: complain, keep going.
        assertThatCode(() -> gate.enforce(kit, descriptor(), source(KitSignaturePolicy.WARN)))
                .doesNotThrowAnyException();
    }

    @Test
    void required_unsignedKit_isRefused() {
        assertThatThrownBy(() ->
                gate.enforce(kit, descriptor(), source(KitSignaturePolicy.REQUIRED)))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("plain")
                .hasMessageContaining("no signature");
    }

    @Test
    void required_signedButNoConfiguredKey_saysSo() throws Exception {
        // A distinct problem from an unsigned kit, with a distinct fix —
        // the message has to name it or the operator adds the wrong thing.
        Files.writeString(kit.resolve(KitSignature.SIGNATURE_FILENAME), """
                algorithm: Ed25519
                keyId: whoever
                treeHash: sha256:0
                signature: AA==
                """);

        assertThatThrownBy(() ->
                gate.enforce(kit, descriptor(), source(KitSignaturePolicy.REQUIRED)))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("publicKey")
                .hasMessageContaining(KitSourceRegistry.SOURCES_PATH);
    }

    @Test
    void nullPolicy_isTreatedAsOff() {
        // A source built without an explicit policy must not accidentally
        // become the strictest one.
        KitSourceDto noPolicy = KitSourceDto.builder()
                .id("s").type(KitSourceType.GIT).url("https://git.example").signature(null)
                .build();
        assertThatCode(() -> gate.enforce(kit, descriptor(), noPolicy))
                .doesNotThrowAnyException();
    }

    private static KitDescriptorDto descriptor() {
        return KitDescriptorDto.builder().name("plain").description("unsigned").build();
    }

    private static KitSourceDto source(KitSignaturePolicy policy) {
        return KitSourceDto.builder()
                .id("test-source")
                .type(KitSourceType.LIBRARY)
                .url("https://library.example")
                .signature(policy)
                .build();
    }
}
