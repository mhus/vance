package de.mhus.vance.brain.kit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.kit.KitSourceDto;
import de.mhus.vance.api.kit.KitSourceType;
import de.mhus.vance.shared.kit.KitException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Which sources may hand over a credential in the clear.
 *
 * <p>See {@code specification/public/kits.md} §9 — the restriction to
 * {@code ODE} is the entire security statement behind
 * {@code encoding: plain}.
 */
class KitPlaintextSecretGateTest {

    private final KitPlaintextSecretGate gate = new KitPlaintextSecretGate();

    @Test
    void plaintextCredentialFromAnOdeSource_passes(@TempDir Path kit) throws IOException {
        writeSetting(kit, "hrafnagud.mount.apiKey", """
                type: PASSWORD
                encoding: plain
                value: "sk-live-abc"
                """);

        assertThatCode(() -> gate.enforce(kit, source(KitSourceType.ODE)))
                .doesNotThrowAnyException();
    }

    @Test
    void plaintextCredentialFromGit_isRefused(@TempDir Path kit) throws IOException {
        // The case the gate exists for. A git repository is a store: anyone
        // who can clone it can read the file, which is exactly what the
        // vault password is there to prevent.
        writeSetting(kit, "some.apiKey", """
                type: PASSWORD
                encoding: plain
                value: "sk-live-abc"
                """);

        assertThatThrownBy(() -> gate.enforce(kit, source(KitSourceType.GIT)))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("some.apiKey.yaml")
                .hasMessageContaining("encoding: plain")
                .hasMessageContaining("ODE");
    }

    @Test
    void plaintextCredentialFromALibrary_isRefused(@TempDir Path kit) throws IOException {
        // Worth its own case rather than folding into the git one: a library
        // is authenticated and per-tenant, which makes it look like ODE from
        // the outside. It is not — it serves stored release archives, so the
        // credential does sit still somewhere.
        writeSetting(kit, "some.apiKey", """
                type: PASSWORD
                encoding: plain
                value: "sk-live-abc"
                """);

        assertThatThrownBy(() -> gate.enforce(kit, source(KitSourceType.LIBRARY)))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("LIBRARY");
    }

    @Test
    void vaultEncodedCredentialFromGit_passes(@TempDir Path kit) throws IOException {
        writeSetting(kit, "some.apiKey", """
                type: PASSWORD
                value: "<vault blob>"
                """);

        assertThatCode(() -> gate.enforce(kit, source(KitSourceType.GIT)))
                .doesNotThrowAnyException();
    }

    @Test
    void serverEncryptedCredentialFromAProjectSource_passes(@TempDir Path kit) throws IOException {
        // Both ends of a project-to-project transfer read the same server key,
        // so the blob is the better wire form than plaintext — nothing is
        // decrypted on the way, not even into the temporary build directory.
        writeSetting(kit, "smtp.pass", """
                type: PASSWORD
                encoding: server
                value: "<server blob>"
                """);

        assertThatCode(() -> gate.enforce(kit, source(KitSourceType.PROJECT)))
                .doesNotThrowAnyException();
    }

    @Test
    void serverEncryptedCredentialFromGit_isRefused(@TempDir Path kit) throws IOException {
        // Not because it would be exposed — it is encrypted — but because it
        // would be unopenable anywhere else. Committed to a repository, this
        // kit installs everywhere as a credential nothing can read, and the
        // first symptom is an opaque failure from whatever consumes it.
        writeSetting(kit, "smtp.pass", """
                type: PASSWORD
                encoding: server
                value: "<server blob>"
                """);

        assertThatThrownBy(() -> gate.enforce(kit, source(KitSourceType.GIT)))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("smtp.pass.yaml")
                .hasMessageContaining("encoding: server")
                .hasMessageContaining("PROJECT");
    }

    @Test
    void plaintextCredentialFromAProjectSource_isRefused(@TempDir Path kit) throws IOException {
        // The two permissions do not overlap: a project source has no reason
        // to ship anything in the clear, since it can always ship the blob.
        writeSetting(kit, "some.apiKey", """
                type: PASSWORD
                encoding: plain
                value: "sk-live-abc"
                """);

        assertThatThrownBy(() -> gate.enforce(kit, source(KitSourceType.PROJECT)))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("ODE");
    }

    @Test
    void kitWithoutSettings_passes(@TempDir Path kit) {
        // The common shape; the gate must not require the directory to exist.
        assertThatCode(() -> gate.enforce(kit, source(KitSourceType.GIT)))
                .doesNotThrowAnyException();
    }

    @Test
    void theWordPlainInADescription_doesNotTrip(@TempDir Path kit) throws IOException {
        // Why the gate parses instead of searching for the word.
        writeSetting(kit, "some.apiKey", """
                type: PASSWORD
                value: "<vault blob>"
                description: "encoding: plain is not allowed for this kit"
                """);

        assertThatCode(() -> gate.enforce(kit, source(KitSourceType.GIT)))
                .doesNotThrowAnyException();
    }

    @Test
    void nonSettingFilesAreIgnored(@TempDir Path kit) throws IOException {
        Files.createDirectories(kit.resolve(KitInstaller.SETTINGS_DIR));
        Files.writeString(kit.resolve(KitInstaller.SETTINGS_DIR).resolve("README.md"),
                "encoding: plain");

        assertThatCode(() -> gate.enforce(kit, source(KitSourceType.GIT)))
                .doesNotThrowAnyException();
    }

    private static void writeSetting(Path kit, String key, String yaml) throws IOException {
        Path dir = kit.resolve(KitInstaller.SETTINGS_DIR);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(key + KitInstaller.SETTING_FILE_SUFFIX), yaml);
    }

    private static KitSourceDto source(KitSourceType type) {
        return KitSourceDto.builder().id("test-source").type(type).build();
    }
}
