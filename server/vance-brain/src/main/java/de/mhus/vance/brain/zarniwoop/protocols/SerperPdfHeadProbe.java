package de.mhus.vance.brain.zarniwoop.protocols;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * HEAD-probes a candidate PDF URL to confirm the server actually
 * serves {@code application/pdf}, not an HTML error page that
 * pretends to be a PDF. Extracted as an interface so
 * {@link SerperInstance} can be tested without real network calls.
 */
public interface SerperPdfHeadProbe {

    record Verdict(
            boolean ok,
            @Nullable String reason,
            @Nullable String finalUrl,
            @Nullable String contentType,
            long contentLength) {

        public static Verdict ok(String finalUrl, String contentType, long len) {
            return new Verdict(true, null, finalUrl, contentType, len);
        }

        public static Verdict fail(String reason) {
            return new Verdict(false, reason, null, null, 0L);
        }
    }

    Verdict head(URI uri, Duration timeout) throws Exception;

    String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/124.0.0.0 Safari/537.36";

    /** Production wiring using the JDK HttpClient. */
    final class JdkPdfHeadProbe implements SerperPdfHeadProbe {

        private final HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(2))
                .build();

        @Override
        public Verdict head(URI uri, Duration timeout) throws Exception {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .header("User-Agent", BROWSER_USER_AGENT)
                    .header("Accept", "application/pdf,*/*;q=0.5")
                    .timeout(timeout)
                    .build();
            HttpResponse<Void> response = http.send(
                    request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status >= 400) {
                return Verdict.fail("status_" + status);
            }
            String contentType = response.headers().firstValue("content-type").orElse("");
            String ct = contentType.toLowerCase();
            int semi = ct.indexOf(';');
            if (semi > 0) ct = ct.substring(0, semi).trim();
            if (!ct.startsWith("application/pdf")) {
                return Verdict.fail("content_type_" + (ct.isEmpty() ? "missing" : ct));
            }
            long length = response.headers().firstValueAsLong("content-length").orElse(0L);
            return Verdict.ok(response.uri().toString(), contentType, length);
        }
    }
}
