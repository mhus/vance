package de.mhus.vance.brain.oauthserver;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientAddressesTest {

    private static MockHttpServletRequest request(String peer, String... forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        for (String value : forwardedFor) {
            request.addHeader("X-Forwarded-For", value);
        }
        return request;
    }

    @Test
    void publicPeer_isTheClient_headerIgnored() {
        assertThat(ClientAddresses.of(request("93.184.215.14", "1.2.3.4"))).isEqualTo("93.184.215.14");
    }

    @Test
    void internalProxyChain_isWalkedToTheFirstPublicAddress() {
        // Caddy (10.42.40.23) -> face-nginx (10.244.0.5) -> brain
        assertThat(ClientAddresses.of(request("10.244.0.5", "93.184.215.14, 10.42.40.23")))
                .isEqualTo("93.184.215.14");
    }

    @Test
    void forgedLeftmostEntry_isNeverReached() {
        // The client sent "X-Forwarded-For: 5.6.7.8"; Caddy appended the real peer.
        assertThat(ClientAddresses.of(request("10.244.0.5", "5.6.7.8, 93.184.215.14, 10.42.40.23")))
                .isEqualTo("93.184.215.14");
    }

    @Test
    void repeatedHeaders_areOneChain() {
        assertThat(ClientAddresses.of(request("10.244.0.5", "93.184.215.14", "10.42.40.23")))
                .isEqualTo("93.184.215.14");
    }

    @Test
    void noHeader_behindAProxy_isTheProxy() {
        assertThat(ClientAddresses.of(request("10.244.0.5"))).isEqualTo("10.244.0.5");
    }

    @Test
    void nonLiteralEntry_stopsTheWalk() {
        assertThat(ClientAddresses.of(request("10.244.0.5", "93.184.215.14, attacker.example, 10.42.40.23")))
                .isEqualTo("10.42.40.23");
    }

    @Test
    void literal_neverResolvesHostNames() {
        assertThat(ClientAddresses.literal("localhost")).isNull();
        assertThat(ClientAddresses.literal("example.com")).isNull();
        assertThat(ClientAddresses.literal("[2001:db8::1]")).isNotNull();
        assertThat(ClientAddresses.literal("10.0.0.1")).isNotNull();
    }
}
