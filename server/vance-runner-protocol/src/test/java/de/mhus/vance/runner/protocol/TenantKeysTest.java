package de.mhus.vance.runner.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TenantKeysTest {

    @TempDir
    Path tmp;

    private static KeyPair ec() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    @Test
    void pem_roundTripsSeveralKeys_inOrder() throws Exception {
        KeyPair first = ec();
        KeyPair second = ec();
        Path file = tmp.resolve("keys.pem");
        Files.writeString(
                file,
                "# comment\n" + TenantKeys.toPem(first.getPublic()) + "\n" + TenantKeys.toPem(second.getPublic()));

        assertThat(TenantKeys.load(file)).containsExactly(first.getPublic(), second.getPublic());
    }

    @Test
    void toPem_isTheStandardArmour() throws Exception {
        String pem = TenantKeys.toPem(ec().getPublic());

        assertThat(pem).startsWith("-----BEGIN PUBLIC KEY-----\n").endsWith("-----END PUBLIC KEY-----\n");
        assertThat(pem.lines().filter(l -> !l.startsWith("-----")).allMatch(l -> l.length() <= 64))
                .isTrue();
    }

    @Test
    void emptyOrBrokenFiles_areRefused() throws Exception {
        Path file = tmp.resolve("keys.pem");
        Files.writeString(file, "nothing here");
        assertThatThrownBy(() -> TenantKeys.load(file))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("No PEM public key");

        Files.writeString(file, "-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----\n");
        assertThatThrownBy(() -> TenantKeys.load(file))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Not a usable EC public key");
    }
}
