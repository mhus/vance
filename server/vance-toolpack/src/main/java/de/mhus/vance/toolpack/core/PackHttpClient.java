package de.mhus.vance.toolpack.core;

import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
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
 * (PEM file path) instead.
 */
public final class PackHttpClient {

    private final Map<TlsConfig, HttpClient> clients = new LinkedHashMap<>();
    private final Duration connectTimeout;

    public PackHttpClient(Duration connectTimeout) {
        this.connectTimeout = connectTimeout == null ? Duration.ofSeconds(10) : connectTimeout;
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
            throw new IllegalStateException(
                    "Failed to build SSLContext for tool-pack: " + e.getMessage(), e);
        }
        return b.build();
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
            TrustManager[] trustAll = new TrustManager[] { new TrustAllManager() };
            ctx.init(null, trustAll, null);
            return ctx;
        } catch (RuntimeException | java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Cannot create trust-all SSLContext", e);
        }
    }

    /**
     * Builds an {@link SSLContext} that trusts the JDK default CAs
     * <i>plus</i> the certificate bundle at {@code pemPath}. Useful
     * for hitting an internal API hosted on a private CA without
     * disabling validation outright.
     */
    private static SSLContext trustCustomCaSslContext(String pemPath) {
        try {
            byte[] pem = Files.readAllBytes(Path.of(pemPath));
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
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tmf.getTrustManagers(), null);
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Cannot load trusted-CA PEM '" + pemPath + "': " + e.getMessage(), e);
        }
    }

    /**
     * Per-pack TLS configuration. Equality + hashCode-keyed so the
     * client cache shares connections across packs with identical
     * settings.
     */
    public record TlsConfig(boolean skipVerification, @Nullable String trustedCaPem) {

        public static final TlsConfig DEFAULT = new TlsConfig(false, null);

        /** Builds a {@code TlsConfig} from a {@code parameters.tls} sub-map. */
        public static TlsConfig fromMap(@Nullable Map<String, Object> tlsBlock) {
            if (tlsBlock == null || tlsBlock.isEmpty()) return DEFAULT;
            Object skip = tlsBlock.get("skipVerification");
            Object pem = tlsBlock.get("trustedCaPemPath");
            return new TlsConfig(
                    skip instanceof Boolean b && b,
                    pem instanceof String s && !s.isBlank() ? s.trim() : null);
        }
    }

    private static final class TrustAllManager implements X509TrustManager {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }

    /** Convenience: HTTP Basic auth header value. */
    public static String basicAuthHeader(String user, String password) {
        String token = (user == null ? "" : user) + ":" + (password == null ? "" : password);
        return "Basic " + java.util.Base64.getEncoder().encodeToString(
                token.getBytes(StandardCharsets.UTF_8));
    }

    /** Convenience: HTTP Bearer auth header value. */
    public static String bearerAuthHeader(String token) {
        return "Bearer " + (token == null ? "" : token);
    }
}
