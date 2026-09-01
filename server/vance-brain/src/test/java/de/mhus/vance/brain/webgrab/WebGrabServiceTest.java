package de.mhus.vance.brain.webgrab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.permission.WriteActor;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WebGrabServiceTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "reading";

    private DocumentService documents;
    private WebGrabService service;
    private final WriteActor actor =
            WriteActor.user(SecurityContext.user("alice", TENANT, List.of()));

    @BeforeEach
    void setUp() {
        documents = mock(DocumentService.class);
        service = new WebGrabService(documents);
        when(documents.findByPath(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(documents.create(anyString(), anyString(), anyString(), any(), any(), any(),
                any(InputStream.class), any(), any(WriteActor.class)))
                .thenAnswer(inv -> {
                    DocumentDocument doc = new DocumentDocument();
                    doc.setPath(inv.getArgument(2));
                    doc.setMimeType(inv.getArgument(5));
                    return doc;
                });
    }

    private String writtenBody() {
        ArgumentCaptor<InputStream> content = ArgumentCaptor.forClass(InputStream.class);
        verify(documents).create(anyString(), anyString(), anyString(), any(), any(), any(),
                content.capture(), any(), any(WriteActor.class));
        try {
            return new String(content.getValue().readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private WebGrabService.Grabbed grabHtml(String html) {
        return service.grab(TENANT, PROJECT, "web", "https://example.com/blog/post",
                "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8),
                null, "alice", actor);
    }

    // ── the type split ────────────────────────────────────────────

    @Test
    void convertsHtmlToMarkdown() {
        WebGrabService.Grabbed grabbed = grabHtml("<h1>Post</h1><p>Body</p>");

        assertThat(grabbed.converted()).isTrue();
        assertThat(grabbed.path()).isEqualTo("web/post.md");
        assertThat(writtenBody()).contains("# Post").contains("Body");
    }

    /**
     * Converting a PDF would lose the thing that makes it a PDF. The bytes go
     * in untouched.
     */
    @Test
    void storesBinariesVerbatim() {
        byte[] pdf = {0x25, 0x50, 0x44, 0x46, 0x2d};

        WebGrabService.Grabbed grabbed = service.grab(TENANT, PROJECT, "web",
                "https://example.com/paper.pdf", "application/pdf", pdf,
                "The Paper", "alice", actor);

        assertThat(grabbed.converted()).isFalse();
        assertThat(grabbed.path()).isEqualTo("web/the-paper.pdf");
        assertThat(writtenBody()).isEqualTo("%PDF-");
    }

    // ── front matter ──────────────────────────────────────────────

    /**
     * The source travels with the document. As a sentence in the body it would
     * not survive the first edit.
     */
    @Test
    void recordsTheSourceInFrontMatter() {
        grabHtml("<h1>Post</h1>");

        assertThat(writtenBody())
                .startsWith("---\n")
                .contains("source: https://example.com/blog/post")
                .contains("grabbedAt: ");
    }

    /**
     * The title is the page's own text. A colon or a newline in it would end
     * the flat header early and spill the rest into the body.
     */
    @Test
    void neutersATitleThatWouldBreakTheHeader() {
        String body = WebGrabService.withFrontMatter(
                "Line one\nkind: evil", "https://x.example/", "body");

        assertThat(body.indexOf("---", 3)).isGreaterThan(body.indexOf("grabbedAt"));
        assertThat(body).contains("title: \"Line one kind: evil\"");
    }

    // ── naming and collisions ─────────────────────────────────────

    /**
     * Grabbing the same article twice is normal — you saw it again and forgot.
     * Overwriting would take whatever the reader added to the first copy with
     * it, silently.
     */
    @Test
    void suffixesRatherThanOverwritingAnExistingDocument() {
        when(documents.findByPath(TENANT, PROJECT, "web/post.md"))
                .thenReturn(Optional.of(new DocumentDocument()));

        assertThat(grabHtml("<h1>Post</h1>").path()).isEqualTo("web/post-2.md");
    }

    /** The check is not the guard — a lost race retries under the next name. */
    @Test
    void survivesLosingTheRaceBetweenCheckAndCreate() {
        when(documents.create(eq(TENANT), eq(PROJECT), eq("web/post.md"), any(), any(), any(),
                any(InputStream.class), any(), any(WriteActor.class)))
                .thenThrow(new DocumentService.DocumentAlreadyExistsException("taken"));

        assertThat(grabHtml("<h1>Post</h1>").path()).isEqualTo("web/post-2.md");
    }

    @Test
    void usesTheCallersTitleOverThePages() {
        WebGrabService.Grabbed grabbed = service.grab(TENANT, PROJECT, "web",
                "https://example.com/blog/post", "text/html",
                "<h1>Page Title</h1>".getBytes(StandardCharsets.UTF_8),
                "My Own Name", "alice", actor);

        assertThat(grabbed.title()).isEqualTo("My Own Name");
        assertThat(grabbed.path()).isEqualTo("web/my-own-name.md");
    }

    // ── the folder ────────────────────────────────────────────────

    @Test
    void defaultsTheFolderAndCleansWhatItIsGiven() {
        assertThat(WebGrabService.normaliseFolder(null)).isEqualTo(WebGrabService.DEFAULT_FOLDER);
        assertThat(WebGrabService.normaliseFolder("  ")).isEqualTo(WebGrabService.DEFAULT_FOLDER);
        assertThat(WebGrabService.normaliseFolder("Reading/Web")).isEqualTo("reading/web");
    }

    /**
     * The folder comes from a client config a person pasted into. The worst a
     * bad value may do is put the document somewhere unexpected inside its own
     * project — never outside it.
     */
    @Test
    void cannotEscapeTheProjectThroughTheFolder() {
        assertThat(WebGrabService.normaliseFolder("../../secrets")).isEqualTo("secrets");
        assertThat(WebGrabService.normaliseFolder("/")).isEqualTo(WebGrabService.DEFAULT_FOLDER);
        assertThat(WebGrabService.normaliseFolder("..")).isEqualTo(WebGrabService.DEFAULT_FOLDER);
    }
}
