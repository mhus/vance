package de.mhus.vance.brain.tools.web;

import java.net.http.HttpClient;
import java.security.cert.X509Certificate;
import java.time.Duration;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Provides a singleton {@link HttpClient} that accepts <em>any</em>
 * TLS certificate, hostname or chain — used as an explicit opt-in
 * from outbound web tools when the caller passes {@code insecure}
 * (e.g. {@code web_fetch(flags="insecure")},
 * {@code doc_import_url(insecure=true)}).
 *
 * <p>Exists because TAD-style server misconfigurations exist: leaf
 * cert is valid, intermediate is missing, AIA chasing
 * ({@code -Dcom.sun.security.enableAIAcaIssuers=true}) handles most
 * of them but not all. When the user has eyeballed the URL and just
 * wants the file, an opt-in trust-all is more useful than a
 * permanent "import a custom cert" detour.
 *
 * <p><b>Security note:</b> this client is intentionally not the
 * default. The factory is only reachable from tools that accept an
 * explicit opt-in flag, and every such call is logged. Do not wire
 * this into background services, schedulers, or any code path that
 * a user can't see and approve.
 */
public final class InsecureHttpClientFactory {

    /** Connect+read timeout — matches {@code WebFetchTool}. */
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    /**
     * Lazy holder pattern — the SSL context is only constructed if
     * something actually asks for it. Keeps test-time class loading
     * out of the trust-all path for engines that never see the flag.
     */
    private static final class Holder {
        static final HttpClient INSTANCE = build();
    }

    private InsecureHttpClientFactory() {}

    public static HttpClient client() {
        return Holder.INSTANCE;
    }

    private static HttpClient build() {
        try {
            TrustManager[] trustAll = new TrustManager[] {
                new X509TrustManager() {
                    @Override public void checkClientTrusted(
                            X509Certificate[] chain, String authType) {}
                    @Override public void checkServerTrusted(
                            X509Certificate[] chain, String authType) {}
                    @Override public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }
            };
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, trustAll, new java.security.SecureRandom());
            return HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(TIMEOUT)
                    .sslContext(ctx)
                    .build();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(
                    "Failed to build trust-all SSL context", e);
        }
    }
}
