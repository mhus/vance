package de.mhus.vance.toolpack.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@code tls.trustedCaPem} parsing and the client it builds (O9: PEM text, never a path). */
class PackHttpClientTlsTest {

    @Test
    void fromMap_pathKey_isRefused() {
        assertThatThrownBy(() -> PackHttpClient.TlsConfig.fromMap(Map.of("trustedCaPemPath", "/dev/zero")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no longer supported");
    }

    @Test
    void fromMap_textWithoutCertificate_isRefused() {
        assertThatThrownBy(() -> PackHttpClient.TlsConfig.fromMap(Map.of("trustedCaPem", "/etc/ssl/ca.pem")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fromMap_oversizedPem_isRefused() {
        String huge = "-----BEGIN CERTIFICATE-----\n" + "A".repeat(PackHttpClient.TlsConfig.MAX_PEM_CHARS);

        assertThatThrownBy(() -> PackHttpClient.TlsConfig.fromMap(Map.of("trustedCaPem", huge)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void client_withRealCaPem_isBuilt() throws Exception {
        PackHttpClient.TlsConfig tls = PackHttpClient.TlsConfig.fromMap(Map.of("trustedCaPem", jdkCaPem()));

        assertThat(new PackHttpClient().client(tls).sslContext()).isNotNull();
    }

    @Test
    void client_withMalformedPem_failsWithoutEchoingContent() {
        PackHttpClient.TlsConfig tls = PackHttpClient.TlsConfig.fromMap(
                Map.of("trustedCaPem", "-----BEGIN CERTIFICATE-----\nnot-base64\n-----END CERTIFICATE-----"));

        assertThatThrownBy(() -> new PackHttpClient().client(tls))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tls.trustedCaPem");
    }

    /** One CA certificate from the running JDK's trust store, PEM-encoded. */
    private static String jdkCaPem() throws Exception {
        KeyStore cacerts = KeyStore.getInstance(KeyStore.getDefaultType());
        Path file = Path.of(System.getProperty("java.home"), "lib", "security", "cacerts");
        try (InputStream in = Files.newInputStream(file)) {
            cacerts.load(in, null);
        }
        String alias = cacerts.aliases().nextElement();
        Certificate cert = cacerts.getCertificate(alias);
        return "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(cert.getEncoded())
                + "\n-----END CERTIFICATE-----\n";
    }
}
