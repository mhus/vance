package de.mhus.vance.brain.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;

/**
 * The trust-all machinery behind {@code tlsInsecure} is deliberately tiny;
 * the tests pin the two properties that matter: the context is usable (an
 * SSLContext the JDK accepts, so a broken init cannot silently degrade into
 * *validated* TLS with a confusing error later), and the sidecar flag
 * reader is fail-closed — anything that is not an explicit {@code true}
 * keeps certificate validation on.
 */
class TlsInsecureTest {

    @Test
    void trustAllContext_isUsable() {
        SSLContext ctx = TlsInsecure.trustAllContext();
        assertThat(ctx).isNotNull();
        assertThat(ctx.getProtocol()).isEqualTo("TLS");
        // Same instance on every call — the lazy holder must not rebuild.
        assertThat(TlsInsecure.trustAllContext()).isSameAs(ctx);
    }

    @Test
    void jdkClientBuilder_buildsWithoutNetwork() {
        // build() proves the pre-configured builder is accepted by the JDK;
        // no request is made here.
        assertThat(TlsInsecure.jdkClientBuilder().build()).isNotNull();
    }

    @Test
    void flagOf_isFailClosed() {
        assertThat(TlsInsecure.flagOf(null)).isFalse();
        assertThat(TlsInsecure.flagOf(Map.of())).isFalse();
        assertThat(TlsInsecure.flagOf(Map.of("tlsInsecure", true))).isTrue();
        assertThat(TlsInsecure.flagOf(Map.of("tlsInsecure", false))).isFalse();
        // Hand-written docs may quote the value.
        assertThat(TlsInsecure.flagOf(Map.of("tlsInsecure", "true"))).isTrue();
        assertThat(TlsInsecure.flagOf(Map.of("tlsInsecure", "false"))).isFalse();
        // Unrelated shapes stay validated.
        assertThat(TlsInsecure.flagOf(Map.of("wireType", "openai"))).isFalse();
        assertThat(TlsInsecure.flagOf(Map.of("tlsInsecure", 1))).isFalse();
        // The flag is read off the whole sidecar, not a curated subset.
        Map<String, Object> sidecar = new HashMap<>();
        sidecar.put("wireType", "openai");
        sidecar.put("displayName", "Coding Proxy");
        assertThat(TlsInsecure.flagOf(sidecar)).isFalse();
        sidecar.put("tlsInsecure", true);
        assertThat(TlsInsecure.flagOf(sidecar)).isTrue();
    }
}
