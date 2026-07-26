package de.mhus.vance.foot.auth;

import java.net.URI;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Rejects plaintext ({@code http://}/{@code ws://}) transport to a
 * non-loopback brain unless explicitly allowed — otherwise the mint-token
 * POST would carry the password in cleartext over the network.
 *
 * <p>Loopback plaintext (local dev) is always permitted; a plaintext base
 * used together with a password triggers a warning either way. Shared by
 * {@code ConnectionService} (connect) and {@link FootAuthService} (login),
 * so the security rule lives in exactly one place.
 */
public final class TransportGuard {

    private TransportGuard() {
    }

    /**
     * @param httpBase       brain HTTP base (may be null/blank → skipped)
     * @param wsBase         brain WebSocket base (may be null/blank → skipped)
     * @param allowInsecure  {@code vance.brain.allowInsecureTransport}
     * @param hasPassword    whether a password would travel over this transport
     * @param warn           sink for the "insecure transport with password" notice
     * @throws IllegalStateException when a non-loopback plaintext base is used
     *                               without {@code allowInsecure}
     */
    public static void assertAllowed(@Nullable String httpBase,
                                     @Nullable String wsBase,
                                     boolean allowInsecure,
                                     boolean hasPassword,
                                     Consumer<String> warn) {
        for (String base : new String[] {httpBase, wsBase}) {
            if (base == null || base.isBlank()) {
                continue;
            }
            URI uri;
            try {
                uri = URI.create(base.strip());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("Malformed brain base URL: " + base, e);
            }
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            boolean plaintext = scheme.equals("http") || scheme.equals("ws");
            if (!plaintext) {
                continue;
            }
            if (!isLoopbackHost(uri.getHost()) && !allowInsecure) {
                throw new IllegalStateException(
                        "Refusing plaintext transport to non-loopback brain '" + base
                                + "' — credentials would go over the wire in cleartext. "
                                + "Use wss://https://, or set vance.brain.allowInsecureTransport=true "
                                + "for an insecure local/dev connection.");
            }
            if (hasPassword) {
                warn.accept("Insecure transport (" + base + ") with a password — "
                        + "credentials are sent in cleartext.");
            }
        }
    }

    private static boolean isLoopbackHost(@Nullable String host) {
        if (host == null || host.isBlank()) {
            return true; // no host = local
        }
        String h = host.replace("[", "").replace("]", "");
        return h.equalsIgnoreCase("localhost")
                || h.startsWith("127.")
                || h.equals("::1")
                || h.equals("0:0:0:0:0:0:0:1");
    }
}
