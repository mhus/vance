package de.mhus.vance.brain.uitheme;

import de.mhus.vance.brain.tools.report.CssSanitizer;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.home.HomeBootstrapService;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Tenant-wide Web-UI customization surface:
 * {@code GET /brain/{tenant}/ui/custom-css} (shell stylesheet) and
 * {@code GET /brain/{tenant}/ui/logo} (header logo). Both read
 * operator-authored documents from the {@code _tenant} system project and
 * serve them to every authenticated user of the tenant.
 *
 * <p><b>Custom CSS.</b> The document lives at {@code _vance/config/custom.css}
 * — the same storage the report themes ({@code _vance/report-themes/<name>.css})
 * and other system-managed configuration documents use. A tenant operator
 * who can write that project can restyle the whole UI for every user of
 * the tenant; that write permission is the gate, there is deliberately no
 * per-document READ check here (see authorisation below).
 *
 * <p><b>Endpoint:</b> {@code GET /brain/{tenant}/ui/custom-css}. Answers
 * {@code text/css} with {@code Cache-Control: private, max-age=60} so a
 * stylesheet change reaches every client within a minute without a
 * re-login. A missing document is <b>not an error</b> — the common state
 * of a fresh tenant — and answers {@code 200} with an empty body, so the
 * client's style element is a no-op rather than a 404 in the console.
 *
 * <p><b>Authorisation.</b> Any authenticated user of the tenant: the
 * access filter already guarantees a valid ACCESS JWT whose tenant claim
 * matches the {@code {tenant}} path variable, and the CSS is a UI asset
 * every tenant browser needs — enforcing READ on the {@code _tenant}
 * project would strip the stylesheet from users without read access
 * there. The write side is the gate: only operators with WRITE on the
 * system project can change what this endpoint serves.
 *
 * <p><b>Sanitisation, scoping, and why this differs from the
 * theme-css path.</b> The served body runs through the same
 * {@link CssSanitizer} that guards the per-document theme CSS: the
 * browser is a real resource loader, so an {@code @import} or a
 * {@code url('https://…')} in the document would fire actual requests
 * (exfil / SSRF) — the sanitizer strips those vectors. The
 * {@code CssScopePrefixer} used by the theme-css endpoint is
 * deliberately <b>not</b> applied: it pins selectors to the markdown
 * preview container, while this stylesheet exists to restyle the whole
 * UI shell. That is the intended power of the feature — a tenant can
 * only affect its own users, and its operators are the ones writing it.
 *
 * <p><b>No project override layer.</b> The lookup is pinned to the
 * {@code _tenant} project; a project cannot shadow
 * {@code _vance/config/custom.css} with its own copy. The UI shell is
 * tenant-global, and a project-scoped stylesheet would make the shell
 * change appearance on every project switch. Revisit only with a spec.
 */
@RestController
@RequestMapping("/brain/{tenant}/ui")
@RequiredArgsConstructor
@Slf4j
public class UiCustomizationController {

    /** Document path inside the {@code _tenant} system project. */
    static final String CUSTOM_CSS_PATH = "_vance/config/custom.css";

    /** Logo candidates under {@code _vance/config/}, in serving priority order. */
    static final String[] LOGO_EXTENSIONS = {"svg", "png", "webp", "jpg"};

    /** Path prefix of the logo document inside the {@code _tenant} system project. */
    static final String LOGO_PATH_PREFIX = "_vance/config/logo.";

    /** Header name — {@code HttpHeaders} has no constant for it in this Spring version. */
    private static final String CONTENT_SECURITY_POLICY_HEADER = "Content-Security-Policy";

    /**
     * Response value that keeps a directly navigated SVG logo from executing
     * anything in the Vance origin — {@code default-src 'none'} blocks all
     * resource loads including scripts and styles inside the SVG document.
     */
    static final String CONTENT_SECURITY_POLICY_VALUE = "default-src 'none'";

    private final DocumentService documentService;

    @GetMapping("/custom-css")
    public ResponseEntity<String> customCss(@PathVariable("tenant") String tenant) {
        String css = documentService
                .lookupCascade(tenant, HomeBootstrapService.TENANT_PROJECT_NAME, CUSTOM_CSS_PATH)
                .map(hit -> CssSanitizer.sanitize(hit.content()))
                .orElse("");
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("text/css;charset=utf-8"))
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePrivate())
                .body(css);
    }

    /**
     * Serves the tenant's header logo, replacing the bundled Vance "v"
     * mark in the topbar for every user of the tenant.
     *
     * <p><b>Lookup.</b> The first match under {@code _vance/config/} wins,
     * in the fixed priority {@code logo.svg} &rarr; {@code logo.png} &rarr;
     * {@code logo.webp} &rarr; {@code logo.jpg} — deterministic, so an
     * operator can swap formats without deleting the old file and the
     * answer never depends on iteration order. The extension is only the
     * lookup key: the response carries the document's own MIME type, so the
     * client never has to guess from the file name.
     *
     * <p><b>Guards.</b> A candidate is only served when its MIME type is
     * {@code image/*} — a renamed non-image file under a {@code logo.*}
     * name is skipped, not served — and so is a value that passes the
     * {@code image/} prefix check but does not parse as a media type
     * ({@link MediaType#parseMediaType} throws): the stored MIME can be
     * a {@code doc_import_url} copy of a foreign {@code Content-Type}
     * header, and an unparseable one is as unusable as a non-image
     * one (Code-Review 12, L1). Two response headers close the SVG
     * question ({@code image/svg+xml} is XML and can carry scripts):
     * {@code X-Content-Type-Options: nosniff} prevents MIME confusion, and
     * {@code Content-Security-Policy: default-src 'none'} keeps a directly
     * navigated SVG document from executing anything in the Vance origin.
     * Loaded through an {@code <img>} (the only intended consumer) SVG
     * scripts are inert anyway.
     *
     * <p><b>Missing logo &rarr; 404, on purpose.</b> The client falls back to
     * its own bundled {@code VanceLogo} component on error — the "v" glyph
     * stays client-side so it keeps its {@code currentColor} tinting
     * (theme-aware light/dark) and remains a single source instead of a
     * server-side copy that drifts. A server-side fallback answer would
     * need a baked-in fill colour and would be wrong in exactly one of the
     * two themes.
     */
    @GetMapping("/logo")
    public ResponseEntity<InputStreamResource> logo(@PathVariable("tenant") String tenant) throws IOException {
        for (String extension : LOGO_EXTENSIONS) {
            Optional<DocumentDocument> candidate = documentService.findByPath(
                    tenant, HomeBootstrapService.TENANT_PROJECT_NAME, LOGO_PATH_PREFIX + extension);
            if (candidate.isEmpty()) {
                continue;
            }
            DocumentDocument document = candidate.get();
            String mimeType = document.getMimeType();
            if (mimeType == null || !mimeType.toLowerCase(Locale.ROOT).startsWith("image/")) {
                log.warn(
                        "Tenant logo candidate '_vance/config/logo.{}' has non-image MIME type '{}' " + "— skipped.",
                        extension,
                        mimeType);
                continue;
            }
            // Parse the media type BEFORE opening the stream: the value
            // comes from the stored document (a doc_import_url copy of a
            // foreign Content-Type header) and can be malformed enough to
            // pass the image/ prefix check yet blow up parseMediaType —
            // with the stream already open that would leak a storage
            // handle per request (Code-Review 12, L1). A candidate whose
            // MIME does not parse is as unusable as a non-image one:
            // skip with a warning, the search continues with the next
            // extension.
            MediaType contentType;
            try {
                contentType = MediaType.parseMediaType(mimeType);
            } catch (RuntimeException e) {
                log.warn(
                        "Tenant logo candidate '_vance/config/logo.{}' has unparseable MIME type '{}' " + "— skipped.",
                        extension,
                        mimeType);
                continue;
            }
            // No try-with-resources: the stream must stay open past the
            // return — Spring reads (and closes) it while writing the
            // response, exactly like the document content endpoint.
            InputStream content = documentService.loadContent(document);
            ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                    .contentType(contentType)
                    .header(CONTENT_SECURITY_POLICY_HEADER, CONTENT_SECURITY_POLICY_VALUE)
                    .header("X-Content-Type-Options", "nosniff")
                    .cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePrivate());
            if (document.getSize() > 0) {
                response.contentLength(document.getSize());
            }
            return response.body(new InputStreamResource(content));
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
