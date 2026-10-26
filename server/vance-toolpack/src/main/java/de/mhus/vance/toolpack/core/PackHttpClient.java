package de.mhus.vance.toolpack.core;

import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import org.jspecify.annotations.Nullable;

/**
 * Wrapper around {@link HttpClient} for tool-pack types (REST API,
 * MCP-HTTP/SSE). Centralises:
 *
 * <ul>
 *   <li>Per-pack {@code skipSslVerification} toggle for self-signed
 *       APIs in dev / on-prem behind private CA.</li>
 *   <li>Per-pack trusted-CA PEM bundle as the safer alternative
 *       to {@code skipSslVerification}.</li>
 *   <li>Connect-/request-timeout defaults shared across pack types.</li>
 *   <li>An optional client decorator — the brain uses it to put its egress
 *       guard into every pack client (pack URLs are tenant config); the foot
 *       talks to the user's own machine and passes none.</li>
 * </ul>
 *
 * <p>Pure Java — no Spring, no Vance internals. Caches built
 * {@link HttpClient} instances per {@link TlsConfig} so two packs
 * with the same TLS settings share one client (TCP-pool hit).
 *
 * <p><b>Security warning:</b> {@link TlsConfig#skipVerification()}
 * disables certificate validation entirely — accepts <i>any</i>
 * certificate including expired or hostname-mismatched ones. Only
 * use for local development against self-signed services. Production
 * setups should ship the API's CA via {@link TlsConfig#trustedCaPem()}
 * (the PEM text) instead.
 */
public final class PackHttpClient {

    private final Map<TlsConfig, HttpClient> clients = new LinkedHashMap<>();
    private final Duration connectTimeout;
    private final UnaryOperator<HttpClient> decorator;

    /**
     * @param decorator applied to every built client; receives a client that does
     *     not follow redirects
     */
    public PackHttpClient(Duration connectTimeout, UnaryOperator<HttpClient> decorator) {
        this.connectTimeout = connectTimeout == null ? Duration.ofSeconds(10) : connectTimeout;
        this.decorator = decorator;
    }

    public PackHttpClient(Duration connectTimeout) {
        this(connectTimeout, UnaryOperator.identity());
    }

    public PackHttpClient() {
        this(Duration.ofSeconds(10));
    }

    /**
     * Returns an {@link HttpClient} configured for the given TLS
     * settings. Repeated calls with equal {@code config} return the
     * same client instance.
     */
    public synchronized HttpClient client(TlsConfig config) {
        TlsConfig effective = config == null ? TlsConfig.DEFAULT : config;
        return clients.computeIfAbsent(effective, this::buildClient);
    }

    private HttpClient buildClient(TlsConfig cfg) {
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(connectTimeout);
        try {
            SSLContext ssl = buildSslContext(cfg);
            if (ssl != null) b.sslContext(ssl);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Failed to build SSLContext for tool-pack: " + e.getMessage(), e);
        }
        return decorator.apply(b.build());
    }

    private static @Nullable SSLContext buildSslContext(TlsConfig cfg) {
        if (cfg.skipVerification()) {
            return trustAllSslContext();
        }
        if (cfg.trustedCaPem() != null && !cfg.trustedCaPem().isBlank()) {
            return trustCustomCaSslContext(cfg.trustedCaPem());
        }
        return null; // JDK default trust store
    }

    /**
     * Builds an {@link SSLContext} that trusts every certificate.
     * Used only when {@link TlsConfig#skipVerification()} is set.
     * <b>Never use against production / external services.</b>
     */
    private static SSLContext trustAllSslContext() {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            TrustManager[] trustAll = new TrustManager[] {new TrustAllManager()};
            ctx.init(null, trustAll, null);
            return ctx;
        } catch (RuntimeException | java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Cannot create trust-all SSLContext", e);
        }
    }

    /**
     * Builds an {@link SSLContext} that trusts exactly the certificates in
     * {@code pem} (the JDK default CAs are not added). Useful for hitting an
     * internal API hosted on a private CA without disabling validation
     * outright.
     */
    private static SSLContext trustCustomCaSslContext(String pemText) {
        try {
            byte[] pem = pemText.getBytes(StandardCharsets.US_ASCII);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            int idx = 0;
            try (ByteArrayInputStream in = new ByteArrayInputStream(pem)) {
                while (in.available() > 0) {
                    java.security.cert.Certificate cert = cf.generateCertificate(in);
                    if (cert instanceof X509Certificate x509) {
                        ks.setCertificateEntry("custom-ca-" + (idx++), x509);
                    }
                }
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tmf.getTrustManagers(), null);
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load tls.trustedCaPem: " + e.getMessage(), e);
        }
    }

    /**
     * Per-pack TLS configuration. Equality + hashCode-keyed so the
     * client cache shares connections across packs with identical
     * settings.
     */
    public record TlsConfig(
            boolean skipVerification, @Nullable String trustedCaPem) {

        public static final TlsConfig DEFAULT = new TlsConfig(false, null);

        /** Upper bound for {@code tls.trustedCaPem} — a CA chain is a few KB. */
        public static final int MAX_PEM_CHARS = 64 * 1024;

        /**
         * Builds a {@code TlsConfig} from a {@code parameters.tls} sub-map.
         * {@code trustedCaPem} carries the PEM text itself. The former
         * {@code trustedCaPemPath} — a file path read on the host running the
         * pack — is refused: whoever writes a pack config must not choose which
         * file the server reads ({@code planning/multi-tenant-isolation.md} O9).
         *
         * @throws IllegalArgumentException on {@code trustedCaPemPath} or a PEM
         *     that is too large or not a certificate
         */
        public static TlsConfig fromMap(@Nullable Map<String, Object> tlsBlock) {
            if (tlsBlock == null || tlsBlock.isEmpty()) return DEFAULT;
            if (tlsBlock.containsKey("trustedCaPemPath")) {
                throw new IllegalArgumentException("tls.trustedCaPemPath is no longer supported — put the"
                        + " certificate itself into tls.trustedCaPem (PEM text)");
            }
            Object skip = tlsBlock.get("skipVerification");
            Object pem = tlsBlock.get("trustedCaPem");
            String pemText = pem instanceof String s && !s.isBlank() ? s.trim() : null;
            if (pemText != null) {
                if (pemText.length() > MAX_PEM_CHARS) {
                    throw new IllegalArgumentException(
                            "tls.trustedCaPem is larger than " + MAX_PEM_CHARS + " characters");
                }
                if (!pemText.contains("-----BEGIN CERTIFICATE-----")) {
                    throw new IllegalArgumentException(
                            "tls.trustedCaPem must contain a PEM certificate" + " (-----BEGIN CERTIFICATE-----)");
                }
            }
            return new TlsConfig(skip instanceof Boolean b && b, pemText);
        }
    }

    private static final class TrustAllManager implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {}

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {}

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    /** Convenience: HTTP Basic auth header value. */
    public static String basicAuthHeader(String user, String password) {
        String token = (user == null ? "" : user) + ":" + (password == null ? "" : password);
        return "Basic " + java.util.Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
    }

    /** Convenience: HTTP Bearer auth header value. */
    public static String bearerAuthHeader(String token) {
        return "Bearer " + (token == null ? "" : token);
    }
}
