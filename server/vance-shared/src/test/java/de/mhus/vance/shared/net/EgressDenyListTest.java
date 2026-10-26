package de.mhus.vance.shared.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

class EgressDenyListTest {

    @Test
    void deniesAddress_ipv4PrefixNotOnByteBoundary() {
        EgressDenyList list = EgressDenyList.parse(List.of("100.64.0.0/10"));

        assertThat(list.deniesAddress(InetAddress.ofLiteral("100.127.255.254"))).isTrue();
        assertThat(list.deniesAddress(InetAddress.ofLiteral("100.128.0.1"))).isFalse();
    }

    @Test
    void deniesAddress_bareLiteral_isSingleAddress() {
        EgressDenyList list = EgressDenyList.parse(List.of("10.42.40.23"));

        assertThat(list.deniesAddress(InetAddress.ofLiteral("10.42.40.23"))).isTrue();
        assertThat(list.deniesAddress(InetAddress.ofLiteral("10.42.40.24"))).isFalse();
    }

    @Test
    void deniesAddress_ipv6Range_doesNotMatchIpv4() {
        EgressDenyList list = EgressDenyList.parse(List.of("fd00::/8"));

        assertThat(list.deniesAddress(InetAddress.ofLiteral("fd12::1"))).isTrue();
        assertThat(list.deniesAddress(InetAddress.ofLiteral("10.0.0.1"))).isFalse();
    }

    @Test
    void deniesHost_isCaseInsensitiveAndExact() {
        EgressDenyList list = EgressDenyList.parse(List.of("Ollama.LAN", " "));

        assertThat(list.deniesHost("ollama.lan")).isTrue();
        assertThat(list.deniesHost("evil.ollama.lan")).isFalse();
    }

    @Test
    void parse_malformedCidr_isRejected() {
        assertThatThrownBy(() -> EgressDenyList.parse(List.of("10.0.0.0/33")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EgressDenyList.parse(List.of("10.0.0.0/x")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
