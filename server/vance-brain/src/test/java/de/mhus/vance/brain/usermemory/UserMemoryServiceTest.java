package de.mhus.vance.brain.usermemory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link UserMemoryService} — compose, learn, and
 * consolidation behaviour. Uses Mockito on {@link DocumentService};
 * no Spring context, no real Mongo.
 */
@ExtendWith(MockitoExtension.class)
class UserMemoryServiceTest {

    private static final String TENANT = "acme";
    private static final String USER_PROJECT = "_user_mike";
    private static final String AUTHOR = "eddie:proc-123";

    @Mock DocumentService documentService;
    UserMemoryService service;

    @BeforeEach
    void setUp() {
        service = new UserMemoryService(documentService);
    }

    // ─── compose ────────────────────────────────────────────────

    @Test
    void composePersonaBlock_returnsNull_whenDocMissing() {
        when(documentService.findByPath(TENANT, USER_PROJECT, UserMemoryService.PERSONA_DOC_PATH))
                .thenReturn(Optional.empty());

        assertThat(service.composePersonaBlock(TENANT, USER_PROJECT)).isNull();
    }

    @Test
    void composePersonaBlock_wrapsText_withHeader() {
        DocumentDocument doc = DocumentDocument.builder()
                .inlineText("Prefers German. Concise replies.")
                .build();
        when(documentService.findByPath(TENANT, USER_PROJECT, UserMemoryService.PERSONA_DOC_PATH))
                .thenReturn(Optional.of(doc));

        String block = service.composePersonaBlock(TENANT, USER_PROJECT);

        assertThat(block)
                .startsWith("## How to talk to this user")
                .contains("Prefers German. Concise replies.")
                .contains("`LEARN scope=persona`");
    }

    @Test
    void composeFactsBlock_returnsNull_whenDocMissing() {
        when(documentService.findByPath(TENANT, USER_PROJECT, UserMemoryService.FACTS_DOC_PATH))
                .thenReturn(Optional.empty());

        assertThat(service.composeFactsBlock(TENANT, USER_PROJECT)).isNull();
    }

    @Test
    void composeFactsBlock_truncatesOversize_snapsToNewline() {
        StringBuilder big = new StringBuilder();
        int target = UserMemoryService.FACTS_PROMPT_BUDGET_CHARS + 2_000;
        int line = 0;
        while (big.length() < target) {
            big.append("[2024-01-").append(String.format("%02d", line % 28 + 1))
                    .append("] Fact-").append(line).append('\n');
            line++;
        }
        DocumentDocument doc = DocumentDocument.builder()
                .inlineText(big.toString())
                .build();
        when(documentService.findByPath(TENANT, USER_PROJECT, UserMemoryService.FACTS_DOC_PATH))
                .thenReturn(Optional.of(doc));

        String block = service.composeFactsBlock(TENANT, USER_PROJECT);

        assertThat(block).isNotNull();
        assertThat(block).contains("(older entries omitted");
        // Ensure body fits the budget plus header + omitted-note overhead.
        assertThat(block.length()).isLessThan(
                UserMemoryService.FACTS_PROMPT_BUDGET_CHARS + 500);
        // Truncation snaps to a newline so we don't slice "[2024-… Fact-N" in half.
        int bodyStart = block.indexOf("\n\n") + 2;
        String firstBodyLine = block.substring(bodyStart).split("\n", 2)[0];
        assertThat(firstBodyLine).startsWith("[2024-");
    }

    // ─── learn ──────────────────────────────────────────────────

    @Test
    void learnPersona_replaceMode_overwritesExisting() {
        // No need to stub findByPath for REPLACE — it doesn't read first.
        service.learnPersona(TENANT, USER_PROJECT,
                "  Be concise. Match the user's language.  ",
                UserMemoryService.MODE_REPLACE, AUTHOR);

        ArgumentCaptor<String> textCap = ArgumentCaptor.forClass(String.class);
        verify(documentService).upsertText(
                eq(TENANT), eq(USER_PROJECT),
                eq(UserMemoryService.PERSONA_DOC_PATH),
                anyString(), any(),
                textCap.capture(),
                eq(AUTHOR));
        assertThat(textCap.getValue())
                .isEqualTo("Be concise. Match the user's language.");
    }

    @Test
    void learnPersona_appendMode_concatenatesWithBlankLine() {
        DocumentDocument existing = DocumentDocument.builder()
                .inlineText("Prefers German.")
                .build();
        when(documentService.findByPath(TENANT, USER_PROJECT, UserMemoryService.PERSONA_DOC_PATH))
                .thenReturn(Optional.of(existing));

        service.learnPersona(TENANT, USER_PROJECT,
                "Likes dark mode.", UserMemoryService.MODE_APPEND, AUTHOR);

        ArgumentCaptor<String> textCap = ArgumentCaptor.forClass(String.class);
        verify(documentService).upsertText(
                eq(TENANT), eq(USER_PROJECT),
                eq(UserMemoryService.PERSONA_DOC_PATH),
                anyString(), any(),
                textCap.capture(),
                eq(AUTHOR));
        assertThat(textCap.getValue())
                .isEqualTo("Prefers German.\n\nLikes dark mode.");
    }

    @Test
    void learnFact_appendsToExistingJournal_withDateStamp() {
        DocumentDocument existing = DocumentDocument.builder()
                .inlineText("[2024-12-01] Likes coffee.")
                .build();
        when(documentService.findByPath(TENANT, USER_PROJECT, UserMemoryService.FACTS_DOC_PATH))
                .thenReturn(Optional.of(existing));

        service.learnFact(TENANT, USER_PROJECT, "Birthday: 4. April", AUTHOR);

        ArgumentCaptor<String> textCap = ArgumentCaptor.forClass(String.class);
        verify(documentService).upsertText(
                eq(TENANT), eq(USER_PROJECT),
                eq(UserMemoryService.FACTS_DOC_PATH),
                anyString(), any(),
                textCap.capture(),
                eq(AUTHOR));
        String stored = textCap.getValue();
        assertThat(stored).startsWith("[2024-12-01] Likes coffee.\n[");
        assertThat(stored).endsWith("] Birthday: 4. April");
    }

    @Test
    void learnFact_firstEntry_persistsWithDateStampOnly() {
        when(documentService.findByPath(TENANT, USER_PROJECT, UserMemoryService.FACTS_DOC_PATH))
                .thenReturn(Optional.empty());

        service.learnFact(TENANT, USER_PROJECT, "First fact.", AUTHOR);

        ArgumentCaptor<String> textCap = ArgumentCaptor.forClass(String.class);
        verify(documentService).upsertText(
                eq(TENANT), eq(USER_PROJECT),
                eq(UserMemoryService.FACTS_DOC_PATH),
                anyString(), any(),
                textCap.capture(),
                eq(AUTHOR));
        assertThat(textCap.getValue())
                .matches("\\[\\d{4}-\\d{2}-\\d{2}\\] First fact\\.");
    }

    // ─── runConsolidation ───────────────────────────────────────

    @Test
    void runConsolidation_skipsLlmCall_whenTooSmall() {
        DocumentDocument tiny = DocumentDocument.builder()
                .inlineText("Single short line.")
                .build();
        when(documentService.findByPath(TENANT, USER_PROJECT, UserMemoryService.PERSONA_DOC_PATH))
                .thenReturn(Optional.of(tiny));

        UserMemoryService.LlmConsolidator llm = (system, current) -> {
            throw new AssertionError("Should not invoke LLM for tiny content");
        };
        service.runConsolidation(UserMemoryService.SCOPE_PERSONA,
                TENANT, USER_PROJECT, AUTHOR, llm);

        verify(documentService, never()).upsertText(
                anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString());
    }

    @Test
    void runConsolidation_persistsResult_whenChanged() {
        String original = "Line 1.\nLine 2 (longer than the trivial-skip threshold, several characters).";
        DocumentDocument doc = DocumentDocument.builder()
                .inlineText(original)
                .build();
        when(documentService.findByPath(TENANT, USER_PROJECT, UserMemoryService.PERSONA_DOC_PATH))
                .thenReturn(Optional.of(doc));

        UserMemoryService.LlmConsolidator llm = (system, current) -> "Consolidated text.";
        service.runConsolidation(UserMemoryService.SCOPE_PERSONA,
                TENANT, USER_PROJECT, AUTHOR, llm);

        ArgumentCaptor<String> textCap = ArgumentCaptor.forClass(String.class);
        verify(documentService).upsertText(
                eq(TENANT), eq(USER_PROJECT),
                eq(UserMemoryService.PERSONA_DOC_PATH),
                anyString(), any(),
                textCap.capture(),
                eq(AUTHOR));
        assertThat(textCap.getValue()).isEqualTo("Consolidated text.");
    }

    @Test
    void runConsolidation_noPersist_whenLlmReturnsSameText() {
        String original = "Line 1.\nLine 2 with enough length to skip the trivial-content branch.";
        DocumentDocument doc = DocumentDocument.builder()
                .inlineText(original)
                .build();
        when(documentService.findByPath(TENANT, USER_PROJECT, UserMemoryService.FACTS_DOC_PATH))
                .thenReturn(Optional.of(doc));

        UserMemoryService.LlmConsolidator llm = (system, current) -> current;
        service.runConsolidation(UserMemoryService.SCOPE_FACT,
                TENANT, USER_PROJECT, AUTHOR, llm);

        verify(documentService, never()).upsertText(
                anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString());
    }

    @Test
    void runConsolidation_stripsCodeFence_fromLlmOutput() {
        String original = "Line 1.\nLine 2 with enough length to skip the trivial-content branch.";
        DocumentDocument doc = DocumentDocument.builder()
                .inlineText(original)
                .build();
        when(documentService.findByPath(TENANT, USER_PROJECT, UserMemoryService.FACTS_DOC_PATH))
                .thenReturn(Optional.of(doc));

        UserMemoryService.LlmConsolidator llm = (system, current) ->
                "```\nClean consolidated text.\n```";
        service.runConsolidation(UserMemoryService.SCOPE_FACT,
                TENANT, USER_PROJECT, AUTHOR, llm);

        ArgumentCaptor<String> textCap = ArgumentCaptor.forClass(String.class);
        verify(documentService).upsertText(
                eq(TENANT), eq(USER_PROJECT),
                eq(UserMemoryService.FACTS_DOC_PATH),
                anyString(), any(),
                textCap.capture(),
                eq(AUTHOR));
        assertThat(textCap.getValue()).isEqualTo("Clean consolidated text.");
    }

    @Test
    void runConsolidation_unknownScope_isNoOp() {
        UserMemoryService.LlmConsolidator llm = (system, current) -> {
            throw new AssertionError("Should not invoke LLM for unknown scope");
        };
        // Pre-stub the findByPath as lenient — runConsolidation should
        // exit before consulting it for an unknown scope.
        lenient().when(documentService.findByPath(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        service.runConsolidation("nonsense",
                TENANT, USER_PROJECT, AUTHOR, llm);

        verify(documentService, never()).upsertText(
                anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString());
    }

    @Test
    void runConsolidation_swallowsLlmException() {
        String original = "Line 1.\nLine 2 long enough to clear the trivial-content threshold easily.";
        DocumentDocument doc = DocumentDocument.builder()
                .inlineText(original)
                .build();
        when(documentService.findByPath(TENANT, USER_PROJECT, UserMemoryService.PERSONA_DOC_PATH))
                .thenReturn(Optional.of(doc));

        UserMemoryService.LlmConsolidator llm = (system, current) -> {
            throw new RuntimeException("upstream broken");
        };
        // No exception leaks; no persistence happens.
        service.runConsolidation(UserMemoryService.SCOPE_PERSONA,
                TENANT, USER_PROJECT, AUTHOR, llm);

        verify(documentService, never()).upsertText(
                anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString());
    }

    @Test
    void scopes_constant_listsBothEntries() {
        assertThat(UserMemoryService.SCOPES).containsExactlyInAnyOrder(
                UserMemoryService.SCOPE_PERSONA,
                UserMemoryService.SCOPE_FACT);
    }

    @Test
    void learnFact_largeJournal_compactlyAppended() {
        // Light sanity check that appending doesn't introduce weird
        // blank-line drift across many entries — the produced string
        // should grow in line-count by exactly one per call.
        DocumentDocument first = DocumentDocument.builder()
                .inlineText("[2024-01-01] First.\n[2024-01-02] Second.")
                .build();
        when(documentService.findByPath(TENANT, USER_PROJECT, UserMemoryService.FACTS_DOC_PATH))
                .thenReturn(Optional.of(first));

        service.learnFact(TENANT, USER_PROJECT, "Third.", AUTHOR);

        ArgumentCaptor<String> textCap = ArgumentCaptor.forClass(String.class);
        verify(documentService).upsertText(
                eq(TENANT), eq(USER_PROJECT),
                eq(UserMemoryService.FACTS_DOC_PATH),
                anyString(), any(),
                textCap.capture(),
                eq(AUTHOR));
        List<String> lines = textCap.getValue().lines().toList();
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).isEqualTo("[2024-01-01] First.");
        assertThat(lines.get(1)).isEqualTo("[2024-01-02] Second.");
        assertThat(lines.get(2)).endsWith("] Third.");
    }
}
