package de.mhus.vance.brain.damogran;

import de.mhus.vance.brain.damogran.DamogranManifest.ImportEntry;
import de.mhus.vance.shared.net.SsrfGuard;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Imports an external {@code http(s)://…} resource into the workspace (WORK
 * RootDir or remote host, via {@code ctx.fileIo()}).
 */
@Component
class HttpImporter implements DamogranImporter {

    private final HttpClient httpClient;

    HttpImporter() {
        // Redirect.NEVER so SsrfGuard.sendGuarded re-checks every hop (F2).
        this.httpClient = SsrfGuard.guardedClientBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    @Override
    public Set<String> schemes() {
        return Set.of("http", "https");
    }

    @Override
    public void doImport(DamogranContext ctx, ImportEntry entry) {
        ctx.requireFileIo("import").writeBytes(entry.to(), httpGet(entry.from()));
    }

    private byte[] httpGet(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .GET()
                    .build();
            HttpResponse<byte[]> response =
                    SsrfGuard.sendGuarded(httpClient, request,
                            SsrfGuard.capped(HttpResponse.BodyHandlers.ofByteArray()));
            if (response.statusCode() / 100 != 2) {
                throw new DamogranException("import HTTP " + response.statusCode() + " for " + url);
            }
            return response.body();
        } catch (SsrfGuard.SsrfException e) {
            throw new DamogranException("import blocked for " + url + ": " + e.getMessage(), e);
        } catch (IOException e) {
            throw new DamogranException("import fetch failed for " + url + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DamogranException("import fetch interrupted for " + url, e);
        }
    }
}
