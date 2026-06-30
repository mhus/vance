package de.mhus.vance.addon.brain.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.script.ScriptExecutor;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.session.SessionService;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link WorkspaceInputService#fileNameFrom(String)} — the
 * deterministic name-to-filename mapping behind the {@code /input} create
 * action. A user-typed extension must be preserved so the document kind
 * follows it; without one the file defaults to {@code .md}.
 */
class WorkspaceInputServiceTest {

    @Test
    void fileNameFrom_userTypedTextExtension_isPreserved() {
        assertThat(WorkspaceInputService.fileNameFrom("yoyoyo.txt"))
                .isEqualTo("yoyoyo.txt");
    }

    @Test
    void fileNameFrom_noExtension_defaultsToMarkdown() {
        assertThat(WorkspaceInputService.fileNameFrom("My Notes"))
                .isEqualTo("my-notes.md");
    }

    @Test
    void fileNameFrom_extensionIsLowerCased() {
        assertThat(WorkspaceInputService.fileNameFrom("Report.TXT"))
                .isEqualTo("report.txt");
    }

    @Test
    void fileNameFrom_dotsInBasename_onlyLastIsExtension() {
        assertThat(WorkspaceInputService.fileNameFrom("my.draft.notes.txt"))
                .isEqualTo("my-draft-notes.txt");
    }

    @Test
    void fileNameFrom_nonExtensionSuffix_treatedAsBasenameAndDefaultsToMarkdown() {
        // The trailing segment is too long to be a file extension, so it
        // stays part of the slug and the file defaults to .md.
        assertThat(WorkspaceInputService.fileNameFrom("notes.somethinglong"))
                .isEqualTo("notes-somethinglong.md");
    }

    @Test
    void fileNameFrom_leadingDotName_keepsItInSlugAndDefaultsToMarkdown() {
        assertThat(WorkspaceInputService.fileNameFrom(".gitignore"))
                .isEqualTo("gitignore.md");
    }

    @Test
    void fileNameFrom_blankName_returnsNull() {
        assertThat(WorkspaceInputService.fileNameFrom("   ")).isNull();
        assertThat(WorkspaceInputService.fileNameFrom(null)).isNull();
    }

    // ---- load / save body + header round-trip --------------------------

    private final DocumentService documentService = mock(DocumentService.class);
    private final ScriptExecutor scriptExecutor = mock(ScriptExecutor.class);
    private final ToolDispatcher toolDispatcher = mock(ToolDispatcher.class);
    private final SessionService sessionService = mock(SessionService.class);
    private final WorkspaceInputService service = new WorkspaceInputService(
            documentService, scriptExecutor, toolDispatcher, sessionService);

    private DocumentDocument docWith(String content) {
        DocumentDocument doc = mock(DocumentDocument.class);
        lenient().when(doc.getId()).thenReturn("doc-1");
        lenient().when(doc.getPath()).thenReturn("notes/intro.md");
        when(documentService.loadContent(doc))
                .thenReturn(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        return doc;
    }

    @Test
    void loadInput_withHeader_stripsHeaderAndReadsConfig() {
        DocumentDocument doc = docWith("---\nonSave: update.js\nsession: true\n---\nhello body\n");
        when(documentService.findByPath("t", "p", "notes/intro.md")).thenReturn(Optional.of(doc));

        WorkspaceInputService.LoadedInput loaded = service.loadInput("t", "p", "notes/intro.md");

        assertThat(loaded.content()).isEqualTo("hello body\n");
        assertThat(loaded.onSaveScript()).isEqualTo("update.js");
        assertThat(loaded.onSaveSession()).isTrue();
    }

    @Test
    void loadInput_missingDoc_returnsEmpty() {
        when(documentService.findByPath("t", "p", "notes/intro.md")).thenReturn(Optional.empty());

        WorkspaceInputService.LoadedInput loaded = service.loadInput("t", "p", "notes/intro.md");

        assertThat(loaded.content()).isEmpty();
        assertThat(loaded.onSaveScript()).isNull();
        assertThat(loaded.onSaveSession()).isFalse();
    }

    @Test
    void saveInput_preservesHeaderAndSkipsScriptWhenNoHook() {
        DocumentDocument doc = docWith("---\ntitle: Intro\n---\nold body");
        when(documentService.findByPath("t", "p", "notes/intro.md")).thenReturn(Optional.of(doc));

        service.saveInput("t", "p", "notes/intro.md", "new body", "user-1");

        assertThat(writtenContent()).isEqualTo("---\ntitle: Intro\n---\nnew body");
        // No onSave key in the header → the script executor must not run.
        verifyNoInteractions(scriptExecutor);
    }

    @Test
    void saveSettings_writesOnSaveHeaderKeysPreservingBody() {
        DocumentDocument doc = docWith("plain body, no header");
        when(documentService.findByPath("t", "p", "notes/intro.md")).thenReturn(Optional.of(doc));

        service.saveSettings("t", "p", "notes/intro.md", "update.js", true, "user-1");

        assertThat(writtenContent())
                .isEqualTo("---\nonSave: update.js\nsession: true\n---\nplain body, no header");
    }

    /** The content handed to the single replaceContent call, as a string. */
    private String writtenContent() {
        ArgumentCaptor<InputStream> captor = ArgumentCaptor.forClass(InputStream.class);
        // Disambiguate the overloaded replaceContent: select the
        // (String, InputStream, String mime, String editorId) variant.
        verify(documentService).replaceContent(
                eq("doc-1"), captor.capture(), any(String.class), any(String.class));
        try {
            return new String(captor.getValue().readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
