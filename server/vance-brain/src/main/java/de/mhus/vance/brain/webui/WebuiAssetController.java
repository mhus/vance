package de.mhus.vance.brain.webui;

import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /brain/{tenant}/projects/{project}/webui/{path…}} — a file of {@code _vance/webui/} for the web UI
 * (icons, logos, images, fonts), resolved through the document cascade. The project is in the path because the cascade
 * begins in the project.
 *
 * <p><b>Authorization.</b> Project READ, like the themes: an asset is configuration, not a user's document, so there is
 * no per-file gate. <b>Failure.</b> Everything that is not served is a plain 404 — unknown type, bad path, not found,
 * too large, an SVG the sanitizer refuses. <b>Headers.</b> The type comes from the file ending and is not sniffed; an
 * SVG carries a restrictive CSP for whoever opens the URL directly. Specification: {@code webui-assets.md}.
 */
@RestController
@RequiredArgsConstructor
public class WebuiAssetController {

    private static final String SVG_CSP = "default-src 'none'; style-src 'unsafe-inline'; sandbox";

    private final WebuiAssetService assets;
    private final RequestAuthority authority;

    @GetMapping("/brain/{tenant}/projects/{project}/webui/{*path}")
    public ResponseEntity<byte[]> asset(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            @PathVariable("path") String path,
            HttpServletRequest request) {

        authority.enforce(request, new Resource.Project(tenant, project), Action.READ);

        return assets.find(tenant, project, path)
                .map(asset -> {
                    ResponseEntity.BodyBuilder ok = ResponseEntity.ok()
                            .contentType(MediaType.parseMediaType(asset.contentType()))
                            .cacheControl(
                                    CacheControl.maxAge(Duration.ofSeconds(300)).cachePrivate())
                            .eTag(asset.etag())
                            .header("X-Content-Type-Options", "nosniff");
                    if (asset.svg()) ok.header("Content-Security-Policy", SVG_CSP);
                    return ok.body(asset.content());
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }
}
