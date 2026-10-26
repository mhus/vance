package de.mhus.vance.shared.net;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Operator deny-list for egress targets ({@code vance.net.ssrf.denied}): the
 * ranges and hosts a tenant must never reach — the cluster's pod and service
 * networks, database hosts, the nodes. Entries are address ranges in CIDR notation
 * ({@code 10.42.0.0/16}, {@code fd00::/8}; a bare IP literal is a single address) or
 * host names (exact, case-insensitive). Only the ranges really protect: a name entry
 * is bypassed by addressing the same target by IP.
 *
 * <p>Operator config only, never a tenant setting.
 */
public final class EgressDenyList {

    private static final EgressDenyList EMPTY = new EgressDenyList(Set.of(), List.of());

    private final Set<String> hosts;
    private final List<Range> ranges;

    private EgressDenyList(Set<String> hosts, List<Range> ranges) {
        this.hosts = hosts;
        this.ranges = ranges;
    }

    public static EgressDenyList empty() {
        return EMPTY;
    }

    /**
     * Parses the configured entries.
     *
     * @throws IllegalArgumentException on a malformed CIDR entry
     */
    public static EgressDenyList parse(Collection<String> entries) {
        Set<String> hosts = new LinkedHashSet<>();
        List<Range> ranges = new ArrayList<>();
        for (String raw : entries) {
            String entry = raw.trim();
            if (entry.isEmpty()) {
                continue;
            }
            if (entry.contains("/") || isIpLiteral(entry)) {
                ranges.add(Range.parse(entry));
            } else {
                hosts.add(entry.toLowerCase(Locale.ROOT));
            }
        }
        return new EgressDenyList(Set.copyOf(hosts), List.copyOf(ranges));
    }

    public boolean isEmpty() {
        return hosts.isEmpty() && ranges.isEmpty();
    }

    /** {@code true} when the URL host is listed by name. */
    public boolean deniesHost(String host) {
        return hosts.contains(host.toLowerCase(Locale.ROOT));
    }

    /** {@code true} when {@code address} lies in a listed range. */
    public boolean deniesAddress(InetAddress address) {
        for (Range range : ranges) {
            if (range.contains(address)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isIpLiteral(String entry) {
        return entry.indexOf(':') >= 0 || entry.matches("[0-9.]+");
    }

    private static final class Range {

        private final byte[] network;
        private final int prefixLength;

        private Range(byte[] network, int prefixLength) {
            this.network = network;
            this.prefixLength = prefixLength;
        }

        static Range parse(String entry) {
            int slash = entry.indexOf('/');
            String address = slash < 0 ? entry : entry.substring(0, slash);
            byte[] bytes;
            try {
                bytes = InetAddress.ofLiteral(address).getAddress();
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("vance.net.ssrf.denied: '" + entry + "' is not an IP/CIDR", e);
            }
            int prefix = bytes.length * 8;
            if (slash >= 0) {
                try {
                    prefix = Integer.parseInt(entry.substring(slash + 1));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("vance.net.ssrf.denied: bad prefix in '" + entry + "'", e);
                }
            }
            if (prefix < 0 || prefix > bytes.length * 8) {
                throw new IllegalArgumentException("vance.net.ssrf.denied: prefix out of range in '" + entry + "'");
            }
            return new Range(bytes, prefix);
        }

        boolean contains(InetAddress address) {
            byte[] candidate = address.getAddress();
            if (candidate.length != network.length) {
                return false;
            }
            int fullBytes = prefixLength / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (candidate[i] != network[i]) {
                    return false;
                }
            }
            int remainingBits = prefixLength % 8;
            if (remainingBits == 0) {
                return true;
            }
            int mask = (0xFF << (8 - remainingBits)) & 0xFF;
            return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}
