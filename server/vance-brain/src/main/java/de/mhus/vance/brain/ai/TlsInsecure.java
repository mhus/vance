package de.mhus.vance.brain.ai;

import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * TLS trust relaxation for AI provider instances behind a private CA.
 *
 * <p>Some gateways sit on a corporate network whose TLS chain is signed by an
 * internal root CA the JDK does not trust — {@code PKIX path building failed}
 * on every chat and listing call. The operator-level fix is importing the CA
 * into the JVM truststore, which is global and restart-bound; the per-instance
 * fix is {@code tlsInsecure: true} in the provider sidecar
 * ({@code _vance/model/<instance>/_provider.yaml}), which routes that
 * instance's <em>chat and model-listing</em> traffic through the trust-all
 * SSL context built here.
 *
 * <p><b>Security note — this is a deliberate opt-in, per instance.</b> The
 * sidecar lives under the reserved {@code _vance/} namespace and therefore
 * needs ADMIN to write, and turning it on disables certificate validation
 * (including hostname and chain) for exactly one named endpoint family. That
 * is an acceptable trade for endpoints on a network the operator already
 * controls — the same reasoning as {@code InsecureHttpClientFactory} for the
 * outbound web tools, except this flag is persistent configuration written by
 * an operator, not a per-call LLM parameter.
 */
@NullMarked
public final class TlsInsecure {

    private TlsInsecure() {}

    /**
     * Trust-all {@link SSLContext} — lazy holder so engines that never see a
     * {@code tlsInsecure} instance pay nothing for it.
     */
    private static final class TrustAllHolder {
        static final SSLContext INSTANCE = build();
    }

    /** The shared trust-all context; accepts any certificate, chain or hostname. */
    public static SSLContext trustAllContext() {
        return TrustAllHolder.INSTANCE;
    }

    /**
     * A fresh {@link java.net.http.HttpClient.Builder} whose TLS layer accepts
     * any certificate. Fresh per call because the JDK builder is mutable and
     * callers (langchain4j's {@code JdkHttpClientBuilder}, the listing client)
     * set their own timeouts and redirect policy on top. The trust-all context
     * is the only thing this pre-sets.
     */
    public static java.net.http.HttpClient.Builder jdkClientBuilder() {
        return HttpClient.newBuilder().sslContext(trustAllContext());
    }

    private static SSLContext build() {
        try {
            TrustManager[] trustAll = new TrustManager[] {
                new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {}

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {}

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }
            };
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, trustAll, new SecureRandom());
            return ctx;
        } catch (Exception e) {
            // init() with a TrustManager array cannot realistically fail, and
            // there is no meaningful fallback — a broken trust-all context
            // must not degrade into silently *validated* TLS.
            throw new IllegalStateException("Failed to initialise trust-all SSL context", e);
        }
    }

    /**
     * Reads the {@code tlsInsecure} flag off a provider sidecar map. Tolerant
     * about the raw type — YAML hands Jackson a {@code Boolean} for
     * {@code true}, but hand-written docs may quote it as a string.
     */
    public static boolean flagOf(@Nullable Map<String, Object> sidecar) {
        if (sidecar == null) return false;
        Object raw = sidecar.get("tlsInsecure");
        if (raw instanceof Boolean b) return b;
        if (raw instanceof String s) {
            return Boolean.parseBoolean(s.trim());
        }
        return false;
    }
}
