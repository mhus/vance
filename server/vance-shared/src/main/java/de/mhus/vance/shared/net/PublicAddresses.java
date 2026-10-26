package de.mhus.vance.shared.net;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

/**
 * The one rule for "is this a globally reachable unicast address". Pure: no
 * configuration, no escape hatch — {@link SsrfGuard} layers the operator settings on
 * top, and callers that classify client addresses ({@code ClientAddresses}) use it
 * as is.
 *
 * <p>IPv4 is a deny list of the special-purpose ranges (RFC 6890 and successors).
 * IPv6 is an allow list — only global unicast {@code 2000::/3} — minus the ranges
 * inside it that embed or translate to IPv4 (6to4 {@code 2002::/16}, Teredo and the
 * other IETF protocol assignments {@code 2001::/23}) or are documentation
 * ({@code 2001:db8::/32}, {@code 3fff::/20}). Everything else — IPv4-compatible
 * {@code ::/96}, IPv4-mapped, NAT64 {@code 64:ff9b::/96} and {@code 64:ff9b:1::/48},
 * unique-local, link-local, multicast — lies outside {@code 2000::/3} and is refused
 * by construction.
 */
public final class PublicAddresses {

    private PublicAddresses() {}

    /** Whether {@code address} is a globally reachable unicast address. */
    public static boolean isPublic(InetAddress address) {
        if (address.isLoopbackAddress()
                || address.isAnyLocalAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            return isPublicV4(b[0] & 0xFF, b[1] & 0xFF, b[2] & 0xFF);
        }
        if (address instanceof Inet6Address) {
            return isPublicV6(b);
        }
        return false;
    }

    private static boolean isPublicV4(int a, int b, int c) {
        return !(a == 0 // "this network" 0.0.0.0/8
                || a == 10 // private
                || (a == 100 && b >= 64 && b <= 127) // CGNAT 100.64.0.0/10
                || a == 127 // loopback
                || (a == 169 && b == 254) // link-local
                || (a == 172 && b >= 16 && b <= 31) // private
                || (a == 192 && b == 0 && c == 0) // IETF protocol assignments 192.0.0.0/24
                || (a == 192 && b == 0 && c == 2) // TEST-NET-1
                || (a == 192 && b == 88 && c == 99) // 6to4 relay anycast
                || (a == 192 && b == 168) // private
                || (a == 198 && (b == 18 || b == 19)) // benchmarking 198.18.0.0/15
                || (a == 198 && b == 51 && c == 100) // TEST-NET-2
                || (a == 203 && b == 0 && c == 113) // TEST-NET-3
                || a >= 224); // multicast 224/4, reserved 240/4, broadcast
    }

    private static boolean isPublicV6(byte[] b) {
        int first = b[0] & 0xFF;
        int second = b[1] & 0xFF;
        int third = b[2] & 0xFF;
        int fourth = b[3] & 0xFF;
        if ((first & 0xE0) != 0x20) {
            return false; // outside global unicast 2000::/3
        }
        if (first == 0x20 && second == 0x01 && third <= 0x01) {
            return false; // 2001::/23 — Teredo 2001::/32, ORCHID, other protocol assignments
        }
        if (first == 0x20 && second == 0x01 && third == 0x0D && fourth == 0xB8) {
            return false; // documentation 2001:db8::/32
        }
        if (first == 0x20 && second == 0x02) {
            return false; // 6to4 2002::/16
        }
        return !(first == 0x3F && second == 0xFF && (third & 0xF0) == 0x00); // documentation 3fff::/20
    }
}
