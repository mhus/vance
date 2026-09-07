package de.mhus.vance.brain.tools.kinds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.age.AgeCipher;
import de.mhus.vance.age.AgeKeys;
import de.mhus.vance.brain.tools.document.DocumentLinkBuilder;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@code doc_encrypt} — the parameter contract, the source guard, the
 * target normalization and the plaintext that actually reaches the create
 * funnel. The crypto round trip itself is covered by vance-age's
 * {@code AgeCipherTest}; here it only has to happen at all.
 */
class DocEncryptToolTest {

    private KindToolSupport support;
    private DocumentService documentService;
    private DocEncryptTool tool;
    private ToolInvocationContext ctx;

    private final AgeKeys.AgeKeyPair pair = AgeKeys.generateKeyPair();

    @BeforeEach
    void setup() {
        support = mock(KindToolSupport.class);
        documentService = mock(DocumentService.class);
        when(support.documentService()).thenReturn(documentService);
        tool = new DocEncryptTool(support, new DocumentLinkBuilder());
        ctx = mock(ToolInvocationContext.class);
        when(ctx.tenantId()).thenReturn("t1");
        when(ctx.userId()).thenReturn("user-1");
        when(ctx.projectId()).thenReturn("p1");
        ProjectDocument project = mock(ProjectDocument.class);
        when(project.getName()).thenReturn("p1");
        EddieContext eddie = mock(EddieContext.class);
        when(support.eddieContext()).thenReturn(eddie);
        when(eddie.resolveProject(any(), any(), anyBoolean())).thenReturn(project);
    }

    private Map<String, Object> params(Object... pairs) {
        Map<String, Object> p = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            p.put((String) pairs[i], pairs[i + 1]);
        }
        return p;
    }

    private void sourceDoc(String kind, String mime, String path, String body) {
        DocumentDocument doc = mock(DocumentDocument.class);
        when(doc.getId()).thenReturn("src-1");
        when(doc.getKind()).thenReturn(kind);
        when(doc.getMimeType()).thenReturn(mime);
        when(doc.getPath()).thenReturn(path);
        when(support.loadDocument(any(), any())).thenReturn(doc);
        when(support.readBody(doc, ctx)).thenReturn(body);
    }

    private DocumentDocument createdDoc() {
        DocumentDocument created = mock(DocumentDocument.class);
        when(created.getId()).thenReturn("new-1");
        when(created.getProjectId()).thenReturn("p1");
        when(created.getPath()).thenReturn("documents/secret.md.age");
        when(created.getKind()).thenReturn("age");
        when(created.getMimeType()).thenReturn("application/age+armored");
        return created;
    }

    // ───────────────────── parameter contract ─────────────────────

    @Test
    void requiresExactlyOneOfFromPathOrContent() {
        assertThatThrownBy(() -> tool.invoke(params("recipients", List.of(pair.recipient())), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("Provide either 'fromPath'");
        assertThatThrownBy(() -> tool.invoke(params(
                "fromPath", "a.md", "content", "x",
                "recipients", List.of(pair.recipient())), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("mutually exclusive");
    }

    @Test
    void requiresRecipients() {
        assertThatThrownBy(() -> tool.invoke(params("content", "x", "toPath", "s"), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("'recipients' is required");
        assertThatThrownBy(() -> tool.invoke(params(
                "content", "x", "toPath", "s", "recipients", List.of("age1notreal")), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("Not a well-formed age recipient");
    }

    @Test
    void contentModeRequiresToPath() {
        assertThatThrownBy(() -> tool.invoke(params(
                "content", "x", "recipients", List.of(pair.recipient())), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("'toPath' is required");
    }

    // ───────────────────── target path normalization ─────────────────────

    @Test
    void fromPathModeDerivesAgeTargetAndAppendsSuffix() {
        sourceDoc(null, "text/markdown", "documents/secret.md", "# hello\n");
        DocumentDocument created = createdDoc();
        when(documentService.create(anyString(), anyString(), anyString(), any(), any(),
                anyString(), any(InputStream.class), any(), any()))
                .thenReturn(created);

        tool.invoke(params(
                "fromPath", "documents/secret.md",
                "recipients", List.of(pair.recipient())), ctx);

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(documentService).create(anyString(), anyString(), pathCaptor.capture(),
                any(), any(), anyString(), any(InputStream.class), any(), any());
        assertThat(pathCaptor.getValue()).isEqualTo("documents/secret.md.age");
    }

    @Test
    void missingAgeSuffixIsAppended() {
        sourceDoc(null, "text/plain", "notes/plain.txt", "body");
        DocumentDocument created = createdDoc();
        when(documentService.create(anyString(), anyString(), anyString(), any(), any(),
                anyString(), any(InputStream.class), any(), any()))
                .thenReturn(created);

        tool.invoke(params(
                "fromPath", "notes/plain.txt",
                "toPath", "notes/copy",
                "recipients", List.of(pair.recipient())), ctx);

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(documentService).create(anyString(), anyString(), pathCaptor.capture(),
                any(), any(), anyString(), any(InputStream.class), any(), any());
        assertThat(pathCaptor.getValue()).isEqualTo("notes/copy.age");
    }

    // ───────────────────── source guard ─────────────────────

    @Test
    void refusesAgeEncryptedSource() {
        sourceDoc("age", "application/age+armored", "documents/x.age", "-----BEGIN-----");

        assertThatThrownBy(() -> tool.invoke(params(
                "fromPath", "documents/x.age",
                "recipients", List.of(pair.recipient())), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("re-encrypting needs the private key");
    }

    // ───────────────────── the create funnel ─────────────────────

    @Test
    void writesArmoredCiphertextWithAgeMimeAndReportsTheResult() throws Exception {
        sourceDoc(null, "text/markdown", "documents/secret.md", "# hello\n");
        DocumentDocument created = createdDoc();
        when(documentService.create(anyString(), anyString(), anyString(), any(), any(),
                anyString(), any(InputStream.class), any(), any()))
                .thenReturn(created);

        Map<String, Object> out = tool.invoke(params(
                "fromPath", "documents/secret.md",
                "recipients", List.of(pair.recipient(), pair.recipient())), ctx);

        ArgumentCaptor<InputStream> bodyCaptor = ArgumentCaptor.forClass(InputStream.class);
        ArgumentCaptor<String> mimeCaptor = ArgumentCaptor.forClass(String.class);
        verify(documentService).create(anyString(), anyString(), anyString(), any(), any(),
                mimeCaptor.capture(), bodyCaptor.capture(), any(), any());
        assertThat(mimeCaptor.getValue()).isEqualTo("application/age+armored");

        String armored = new String(
                bodyCaptor.getValue().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(armored)
                .startsWith("-----BEGIN AGE ENCRYPTED FILE-----")
                .contains("-----END AGE ENCRYPTED FILE-----");
        // The round trip through the very recipients the tool was given.
        assertThat(AgeCipher.decryptArmored(armored,
                new de.mhus.vance.age.AgeSecrets(List.of(pair.identity()), List.of())))
                .isEqualTo("# hello\n");

        assertThat(out).containsEntry("path", "documents/secret.md.age");
        assertThat(out).containsEntry("kind", "age");
        assertThat(out).containsEntry("recipientCount", 1);
        assertThat(out).containsKey("markdownLink");
        assertThat(out).containsEntry("fromPath", "documents/secret.md");
    }

    @Test
    void contentModeEncryptsTheGivenText() throws Exception {
        DocumentDocument created = createdDoc();
        when(documentService.create(anyString(), anyString(), anyString(), any(), any(),
                anyString(), any(InputStream.class), any(), any()))
                .thenReturn(created);

        tool.invoke(params(
                "content", "generated secret",
                "toPath", "documents/generated.md.age",
                "recipients", List.of(pair.recipient())), ctx);

        ArgumentCaptor<InputStream> bodyCaptor = ArgumentCaptor.forClass(InputStream.class);
        verify(documentService).create(anyString(), anyString(), anyString(), any(), any(),
                anyString(), bodyCaptor.capture(), any(), any());
        String armored = new String(
                bodyCaptor.getValue().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(AgeCipher.decryptArmored(armored,
                new de.mhus.vance.age.AgeSecrets(List.of(pair.identity()), List.of())))
                .isEqualTo("generated secret");
    }

    @Test
    void existingTargetSurfacesAsClearError() throws Exception {
        sourceDoc(null, "text/markdown", "documents/secret.md", "x");
        when(documentService.create(anyString(), anyString(), anyString(), any(), any(),
                anyString(), any(InputStream.class), any(), any()))
                .thenThrow(new DocumentService.DocumentAlreadyExistsException("exists"));

        assertThatThrownBy(() -> tool.invoke(params(
                "fromPath", "documents/secret.md",
                "recipients", List.of(pair.recipient())), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("never overwrites");
    }
}
