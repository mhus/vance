package de.mhus.vance.brain.uitheme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

/**
 * What this class owns: the storage location (fixed path in the
 * {@code _tenant} project), the fail-open answer for a missing document,
 * and the response envelope (content type, caching, empty body). What it
 * does <b>not</b> own: the filter rules themselves — those are
 * {@code CssSanitizer}'s contract and tested there; here we only pin that
 * the served body is the sanitizer's output, not the raw document.
 */
class UiCustomizationControllerTest {

    private DocumentService documentService;
    private UiCustomizationController controller;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        controller = new UiCustomizationController(documentService);
    }

    @Test
    void missingDocumentAnswersEmptyCssNotAnError() {
        when(documentService.lookupCascade("acme", "_tenant", UiCustomizationController.CUSTOM_CSS_PATH))
                .thenReturn(Optional.empty());

        ResponseEntity<String> response = controller.customCss("acme");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEmpty();
    }

    @Test
    void servesCssAsTextCssWithShortPrivateCache() {
        when(documentService.lookupCascade("acme", "_tenant", UiCustomizationController.CUSTOM_CSS_PATH))
                .thenReturn(Optional.of(new LookupResult(
                        UiCustomizationController.CUSTOM_CSS_PATH,
                        "body { color: red; }",
                        LookupResult.Source.VANCE,
                        null)));

        ResponseEntity<String> response = controller.customCss("acme");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.valueOf("text/css;charset=utf-8"));
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("max-age=60, private");
        assertThat(response.getBody()).isEqualTo("body { color: red; }");
    }

    @Test
    void sanitisesTheDocumentBeforeServingIt() {
        // The browser is a real resource loader — an @import or an
        // external url() in the tenant document would fire requests.
        // The endpoint must not serve the raw document even when the
        // document is only writable by tenant operators.
        String hostile = "@import 'https://evil.example/x.css';\n"
                + "body { background: url('https://evil.example/pixel'); color: red; }";
        when(documentService.lookupCascade("acme", "_tenant", UiCustomizationController.CUSTOM_CSS_PATH))
                .thenReturn(Optional.of(new LookupResult(
                        UiCustomizationController.CUSTOM_CSS_PATH, hostile, LookupResult.Source.VANCE, null)));

        String body = controller.customCss("acme").getBody();

        assertThat(body).doesNotContain("evil.example");
        assertThat(body).contains("color: red");
    }

    @Test
    void lookupIsPinnedToTheTenantProjectNotTheCallerProject() {
        // No project layer: a project must not be able to shadow the
        // stylesheet, or the shell would change appearance on every
        // project switch. The only project in the lookup is _tenant.
        when(documentService.lookupCascade("acme", "_tenant", UiCustomizationController.CUSTOM_CSS_PATH))
                .thenReturn(Optional.empty());

        controller.customCss("acme");

        verify(documentService).lookupCascade("acme", "_tenant", UiCustomizationController.CUSTOM_CSS_PATH);
    }

    // ──────────────────── logo endpoint ────────────────────

    /**
     * A mocked logo candidate: fixed MIME type, fixed bytes, size matches
     * the bytes, content served from a fresh stream (the real service
     * opens storage per call).
     */
    private DocumentDocument logoDocument(String mimeType, byte[] bytes) {
        DocumentDocument doc = mock(DocumentDocument.class);
        when(doc.getMimeType()).thenReturn(mimeType);
        when(doc.getSize()).thenReturn((long) bytes.length);
        when(documentService.loadContent(doc)).thenReturn(new ByteArrayInputStream(bytes));
        return doc;
    }

    private void noLogoAnywhere() {
        for (String extension : UiCustomizationController.LOGO_EXTENSIONS) {
            when(documentService.findByPath("acme", "_tenant", UiCustomizationController.LOGO_PATH_PREFIX + extension))
                    .thenReturn(Optional.empty());
        }
    }

    @Test
    void logoWithoutAnyCandidateIs404NotAnEmptyImage() throws IOException {
        noLogoAnywhere();

        assertThatThrownBy(() -> controller.logo("acme"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(404);
    }

    @Test
    void logoServesTheFirstCandidateInFixedPriorityOrder() throws IOException {
        // svg beats png: an operator swapping formats uploads the new one
        // first — the answer must not depend on which row Mongo returns first.
        DocumentDocument svg = logoDocument("image/svg+xml", "<svg/>".getBytes(StandardCharsets.UTF_8));
        DocumentDocument png = logoDocument("image/png", "PNG".getBytes(StandardCharsets.UTF_8));
        when(documentService.findByPath("acme", "_tenant", "_vance/config/logo.svg"))
                .thenReturn(Optional.of(svg));
        when(documentService.findByPath("acme", "_tenant", "_vance/config/logo.png"))
                .thenReturn(Optional.of(png));

        ResponseEntity<InputStreamResource> response = controller.logo("acme");

        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.valueOf("image/svg+xml"));
        try (InputStream body = response.getBody().getInputStream()) {
            assertThat(new String(body.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("<svg/>");
        }
    }

    @Test
    void logoFallsThroughToPngWhenSvgIsMissing() throws IOException {
        DocumentDocument png = logoDocument("image/png", "PNG".getBytes(StandardCharsets.UTF_8));
        when(documentService.findByPath("acme", "_tenant", "_vance/config/logo.svg"))
                .thenReturn(Optional.empty());
        when(documentService.findByPath("acme", "_tenant", "_vance/config/logo.png"))
                .thenReturn(Optional.of(png));

        ResponseEntity<InputStreamResource> response = controller.logo("acme");

        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
    }

    @Test
    void logoSkipsCandidatesWhoseMimeIsNotAnImage() throws IOException {
        // A renamed HTML file under a logo.* name must not be served as
        // anything — the image/* gate is the whole defence, the response
        // headers only harden what passes it.
        DocumentDocument html = logoDocument("text/html", "<script/>".getBytes(StandardCharsets.UTF_8));
        DocumentDocument png = logoDocument("image/png", "PNG".getBytes(StandardCharsets.UTF_8));
        when(documentService.findByPath("acme", "_tenant", "_vance/config/logo.svg"))
                .thenReturn(Optional.of(html));
        when(documentService.findByPath("acme", "_tenant", "_vance/config/logo.png"))
                .thenReturn(Optional.of(png));

        ResponseEntity<InputStreamResource> response = controller.logo("acme");

        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
    }

    @Test
    void logoSkipsCandidatesWhoseImageMimeDoesNotParse() throws IOException {
        // The stored MIME can be a doc_import_url copy of a foreign
        // Content-Type header: "image/" passes the image/* prefix gate
        // but does not parse as a MediaType. The candidate must be
        // skipped (search continues, storage never opened for it), not
        // blow the endpoint up after the stream is already open
        // (Code-Review 12, L1).
        DocumentDocument malformed = logoDocument("image/", "junk".getBytes(StandardCharsets.UTF_8));
        DocumentDocument png = logoDocument("image/png", "PNG".getBytes(StandardCharsets.UTF_8));
        when(documentService.findByPath("acme", "_tenant", "_vance/config/logo.svg"))
                .thenReturn(Optional.of(malformed));
        when(documentService.findByPath("acme", "_tenant", "_vance/config/logo.png"))
                .thenReturn(Optional.of(png));

        ResponseEntity<InputStreamResource> response = controller.logo("acme");

        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        // The malformed candidate's storage was never opened.
        verify(documentService, never()).loadContent(malformed);
    }

    @Test
    void logoAnswersWithTheSvgHardeningHeaders() throws IOException {
        // SVG is XML and can carry scripts; a direct navigation to this
        // URL must not execute anything in the Vance origin. nosniff
        // closes the MIME-confusion path on top.
        DocumentDocument svg = logoDocument("image/svg+xml", "<svg/>".getBytes(StandardCharsets.UTF_8));
        when(documentService.findByPath("acme", "_tenant", "_vance/config/logo.svg"))
                .thenReturn(Optional.of(svg));

        ResponseEntity<InputStreamResource> response = controller.logo("acme");

        assertThat(response.getHeaders().getCacheControl()).isEqualTo("max-age=60, private");
        assertThat(response.getHeaders().get("Content-Security-Policy")).containsExactly("default-src 'none'");
        assertThat(response.getHeaders().get("X-Content-Type-Options")).containsExactly("nosniff");
        assertThat(response.getHeaders().getContentLength()).isEqualTo(6);
    }
}
