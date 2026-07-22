package de.mhus.vance.shared.net;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;

/**
 * Guards the SSRF egress policy (code-review F2). Uses literal IPs and
 * {@code localhost} so no real DNS / network is required.
 */
class SsrfGuardTest {

    @Test
    void rejects_nonHttpSchemes() {
        for (String url : new String[] {
                "file:///etc/passwd", "ftp://host/x", "gopher://host",
                "javascript:alert(1)", "jar:http://host!/x"}) {
            assertThatThrownBy(() -> SsrfGuard.assertAllowed(url))
                    .as(url)
                    .isInstanceOf(SsrfGuard.SsrfException.class);
        }
    }

    @Test
    void rejects_loopback_linkLocal_private_wildcard() {
        for (String url : new String[] {
                "http://127.0.0.1/x",
                "http://127.5.6.7/x",
                "http://[::1]/x",
                "http://169.254.169.254/latest/meta-data/",   // cloud metadata
                "http://0.0.0.0/x",
                "http://10.0.0.5/x",
                "http://172.16.4.4/x",
                "http://192.168.1.1/x",
                "http://[fc00::1]/x",                           // IPv6 unique-local
                "http://[fe80::1]/x"}) {                        // IPv6 link-local
            assertThatThrownBy(() -> SsrfGuard.assertAllowed(url))
                    .as(url)
                    .isInstanceOf(SsrfGuard.SsrfException.class);
        }
    }

    @Test
    void rejects_localhost_hostname() {
        assertThatThrownBy(() -> SsrfGuard.assertAllowed("http://localhost:9090/actuator"))
                .isInstanceOf(SsrfGuard.SsrfException.class);
    }

    @Test
    void allows_publicLiteralIp() {
        assertThatCode(() -> SsrfGuard.assertAllowed("https://1.1.1.1/x"))
                .doesNotThrowAnyException();
        assertThatCode(() -> SsrfGuard.assertAllowed("http://8.8.8.8/dns"))
                .doesNotThrowAnyException();
    }

    @Test
    void isBlocked_flagsIpv4MappedLoopback() throws UnknownHostException {
        // ::ffff:127.0.0.1 must be treated as loopback, not public.
        InetAddress mapped = InetAddress.getByName("::ffff:127.0.0.1");
        org.junit.jupiter.api.Assertions.assertTrue(SsrfGuard.isBlocked(mapped));
    }

    @Test
    void rejects_malformedUrl() {
        assertThatThrownBy(() -> SsrfGuard.assertAllowed("http://"))
                .isInstanceOf(SsrfGuard.SsrfException.class);
    }
}
