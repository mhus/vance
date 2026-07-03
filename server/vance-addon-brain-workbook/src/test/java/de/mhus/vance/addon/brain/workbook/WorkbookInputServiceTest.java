package de.mhus.vance.addon.brain.workbook;

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
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link WorkbookInputService}. Two groups:
 *
 * <ul>
 *   <li>{@link WorkbookInputService#fileNameFrom(String)} — the deterministic
 *       name-to-filename mapping behind the {@code /input} create action. A
 *       user-typed extension must be preserved so the document kind follows it;
 *       without one the file defaults to {@code .md}.</li>
 *   <li>{@code loadInput} / {@code saveInput} — the bound file is treated
 *       verbatim (whole content is the value, no front-matter header split).
 *       The recompute {@code saveScript} comes from the block fence — no
 *       {@code $meta.onSave}, no session, no fallback.</li>
 * </ul>
 */
class WorkbookInputServiceTest {

    @Test
    void fileNameFrom_userTypedTextExtension_isPreserved() {
        assertThat(WorkbookInputService.fileNameFrom("yoyoyo.txt"))
                .isEqualTo("yoyoyo.txt");
    }

    @Test
    void fileNameFrom_noExtension_defaultsToMarkdown() {
        assertThat(WorkbookInputService.fileNameFrom("My Notes"))
                .isEqualTo("my-notes.md");
    }

    @Test
    void fileNameFrom_extensionIsLowerCased() {
        assertThat(WorkbookInputService.fileNameFrom("Report.TXT"))
                .isEqualTo("report.txt");
    }

    @Test
    void fileNameFrom_dotsInBasename_onlyLastIsExtension() {
        assertThat(WorkbookInputService.fileNameFrom("my.draft.notes.txt"))
                .isEqualTo("my-draft-notes.txt");
    }

    @Test
    void fileNameFrom_nonExtensionSuffix_treatedAsBasenameAndDefaultsToMarkdown() {
        // The trailing segment is too long to be a file extension, so it
        // stays part of the slug and the file defaults to .md.
        assertThat(WorkbookInputService.fileNameFrom("notes.somethinglong"))
                .isEqualTo("notes-somethinglong.md");
    }

    @Test
    void fileNameFrom_leadingDotName_keepsItInSlugAndDefaultsToMarkdown() {
        assertThat(WorkbookInputService.fileNameFrom(".gitignore"))
                .isEqualTo("gitignore.md");
    }

    @Test
    void fileNameFrom_blankName_returnsNull() {
        assertThat(WorkbookInputService.fileNameFrom("   ")).isNull();
        assertThat(WorkbookInputService.fileNameFrom(null)).isNull();
    }

    // ---- load / save content (verbatim, fence-only saveScript) ---------

    private final DocumentService documentService = mock(DocumentService.class);
    private final ScriptExecutor scriptExecutor = mock(ScriptExecutor.class);
    private final ToolDispatcher toolDispatcher = mock(ToolDispatcher.class);
    private final SessionService sessionService = mock(SessionService.class);
    private final WorkbookInputService service = new WorkbookInputService(
            documentService, scriptExecutor, toolDispatcher, sessionService);

    private DocumentDocument docWith(String content) {
        DocumentDocument doc = mock(DocumentDocument.class);
        lenient().when(doc.getId()).thenReturn("doc-1");
        lenient().when(doc.getPath()).thenReturn("notes/intro.md");
        // Only loadInput reads content back; the verbatim save path does not.
        lenient().when(documentService.loadContent(doc))
                .thenReturn(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        return doc;
    }

    @Test
    void loadInput_returnsWholeContentVerbatim() {
        // A leading '---' block is NOT a header for input — it is content.
        DocumentDocument doc = docWith("---\ntitle: Intro\n---\nhello body\n");
        when(documentService.findByPath("t", "p", "notes/intro.md")).thenReturn(Optional.of(doc));

        assertThat(service.loadInput("t", "p", "notes/intro.md"))
                .isEqualTo("---\ntitle: Intro\n---\nhello body\n");
    }

    @Test
    void loadInput_missingDoc_returnsEmpty() {
        when(documentService.findByPath("t", "p", "notes/intro.md")).thenReturn(Optional.empty());

        assertThat(service.loadInput("t", "p", "notes/intro.md")).isEmpty();
    }

    @Test
    void saveInput_writesContentVerbatimAndSkipsScriptWhenNoSaveScript() {
        DocumentDocument doc = docWith("old body");
        when(documentService.findByPath("t", "p", "notes/intro.md")).thenReturn(Optional.of(doc));

        service.saveInput("t", "p", "notes/intro.md", "new body", null, false, "user-1");

        assertThat(writtenContent()).isEqualTo("new body");
        // No fence saveScript → the script executor must not run.
        verifyNoInteractions(scriptExecutor);
        verifyNoInteractions(sessionService);
    }

    @Test
    void saveInput_blankSaveScript_skipsScript() {
        DocumentDocument doc = docWith("plain body");
        when(documentService.findByPath("t", "p", "notes/intro.md")).thenReturn(Optional.of(doc));

        service.saveInput("t", "p", "notes/intro.md", "changed", "   ", false, "user-1");

        assertThat(writtenContent()).isEqualTo("changed");
        verifyNoInteractions(scriptExecutor);
    }

    @Test
    void saveInput_sessionOptIn_reusesOrCreatesPerInputSystemSession() {
        DocumentDocument doc = docWith("body");
        when(documentService.findByPath("t", "p", "notes/intro.md")).thenReturn(Optional.of(doc));
        DocumentDocument scriptDoc = mock(DocumentDocument.class);
        lenient().when(scriptDoc.getPath()).thenReturn("notes/update.js");
        when(documentService.findByPath("t", "p", "notes/update.js"))
                .thenReturn(Optional.of(scriptDoc));
        when(documentService.loadContent(scriptDoc))
                .thenReturn(new ByteArrayInputStream("// recompute".getBytes(StandardCharsets.UTF_8)));
        // No existing system session → the service creates one.
        when(sessionService.findSystemSession("t", "p", "_input_notes_intro.md"))
                .thenReturn(Optional.empty());
        SessionDocument created = mock(SessionDocument.class);
        when(created.getSessionId()).thenReturn("sess-1");
        when(sessionService.create(
                eq("t"), eq("user-1"), eq("p"), eq("_input_notes_intro.md"),
                any(String.class), eq("workbook-input"), eq(null), eq(true)))
                .thenReturn(created);

        service.saveInput("t", "p", "notes/intro.md", "body", "vance:update.js", true, "user-1");

        verify(sessionService).create(
                eq("t"), eq("user-1"), eq("p"), eq("_input_notes_intro.md"),
                any(String.class), eq("workbook-input"), eq(null), eq(true));
        verify(sessionService).markBootstrapped("sess-1");
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
