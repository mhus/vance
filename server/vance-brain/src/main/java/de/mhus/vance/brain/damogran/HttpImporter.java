package de.mhus.vance.brain.damogran;

import de.mhus.vance.brain.damogran.DamogranManifest.ImportEntry;
import de.mhus.vance.shared.workspace.WorkspaceService;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Imports an external {@code http(s)://…} resource into the workspace.
 */
@Component
class HttpImporter implements DamogranImporter {

    private final WorkspaceService workspaceService;
    private final HttpClient httpClient;

    HttpImporter(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public Set<String> schemes() {
        return Set.of("http", "https");
    }

    @Override
    public void doImport(DamogranContext ctx, ImportEntry entry) {
        DamogranWorkspaceIo.requireWorkRoot(ctx, "import");
        DamogranWorkspaceIo.writeBytes(workspaceService, ctx, entry.to(), httpGet(entry.from()));
    }

    private byte[] httpGet(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .GET()
                    .build();
            HttpResponse<byte[]> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                throw new DamogranException("import HTTP " + response.statusCode() + " for " + url);
            }
            return response.body();
        } catch (IOException e) {
            throw new DamogranException("import fetch failed for " + url + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DamogranException("import fetch interrupted for " + url, e);
        }
    }
}
