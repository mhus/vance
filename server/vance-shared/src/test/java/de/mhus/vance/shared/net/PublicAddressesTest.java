package de.mhus.vance.shared.net;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class PublicAddressesTest {

    @Test
    void isPublic_allowsGlobalUnicast() {
        for (String global : new String[] {
            "93.184.215.14",
            "8.8.8.8",
            "1.1.1.1",
            "100.63.255.255", // just below CGNAT
            "100.128.0.1", // just above CGNAT
            "172.32.0.1", // just above 172.16/12
            "198.17.255.255", // just below 198.18/15
            "198.20.0.1", // just above 198.18/15
            "223.255.255.254", // just below multicast
            "2606:4700:4700::1111",
            "2a00:1450:4001:80b::200e",
            "2001:200::1", // just above 2001::/23
        }) {
            assertThat(PublicAddresses.isPublic(InetAddress.ofLiteral(global)))
                    .as(global)
                    .isTrue();
        }
    }

    @Test
    void isPublic_excludesEverySpecialPurposeRange() {
        for (String internal : new String[] {
            // IPv4
            "0.0.0.0", // unspecified
            "0.1.2.3", // this network 0/8
            "10.0.0.1", // private
            "10.42.40.10", // the cluster
            "100.64.0.1", // CGNAT
            "100.127.255.254", // CGNAT upper edge
            "127.0.0.1", // loopback
            "127.255.255.254",
            "169.254.169.254", // link-local / cloud metadata
            "172.16.0.1", // private
            "172.31.255.254",
            "192.0.0.1", // IETF protocol assignments 192.0.0.0/24
            "192.0.0.170", // NAT64 discovery
            "192.0.2.1", // TEST-NET-1
            "192.88.99.1", // 6to4 relay anycast
            "192.168.1.1", // private
            "198.18.0.1", // benchmarking
            "198.19.255.254",
            "198.51.100.1", // TEST-NET-2
            "203.0.113.1", // TEST-NET-3
            "224.0.0.1", // multicast
            "239.255.255.250",
            "240.0.0.1", // reserved 240/4
            "255.255.255.255", // broadcast
            // IPv6
            "::", // unspecified
            "::1", // loopback
            "::a00:1", // IPv4-compatible ::10.0.0.1
            "::5db8:d70e", // IPv4-compatible of a public v4 — still refused
            "::ffff:10.0.0.1", // IPv4-mapped
            "64:ff9b::a00:1", // NAT64 well-known prefix
            "64:ff9b::5db8:d70e", // NAT64 of a public v4 — refused, translator is ours
            "64:ff9b:1::a00:1", // NAT64 local-use 64:ff9b:1::/48
            "100::1", // discard-only
            "2001::1", // Teredo 2001::/32
            "2001:0:4136:e378:8000:63bf:3fff:fdd2", // Teredo
            "2001:10::1", // ORCHID
            "2001:db8::1", // documentation
            "2002:a00:1::1", // 6to4 of 10.0.0.1
            "2002:5db8:d70e::1", // 6to4 of a public v4
            "3fff::1", // documentation 3fff::/20
            "fc00::1", // unique local
            "fd00::1",
            "fe80::1", // link-local
            "fec0::1", // site-local (deprecated)
            "ff02::1", // multicast
        }) {
            assertThat(PublicAddresses.isPublic(InetAddress.ofLiteral(internal)))
                    .as(internal)
                    .isFalse();
        }
    }
}
