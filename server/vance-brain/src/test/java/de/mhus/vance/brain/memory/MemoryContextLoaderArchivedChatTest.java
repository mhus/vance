package de.mhus.vance.brain.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.context.ReadStateService;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.memory.MemoryDocument;
import de.mhus.vance.shared.memory.MemoryKind;
import de.mhus.vance.shared.memory.MemoryService;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.settings.LanguageResolver;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ARCHIVED_CHAT-Replay in {@link MemoryContextLoader}: after a sliding-
 * window compaction archives older messages, the resulting summary
 * memory must be re-injected into the prompt so the LLM keeps the gist
 * of the prior conversation. See {@code planning/memory-compaction.md} §4.
 */
class MemoryContextLoaderArchivedChatTest {

    private SettingService settingService;
    private SessionService sessionService;
    private DocumentService documentService;
    private LanguageResolver languageResolver;
    private ReadStateService readStateService;
    private MemoryService memoryService;
    private ThinkProcessService thinkProcessService;
    private MemoryContextLoader loader;

    @BeforeEach
    void setUp() {
        settingService = mock(SettingService.class);
        sessionService = mock(SessionService.class);
        documentService = mock(DocumentService.class);
        languageResolver = mock(LanguageResolver.class);
        readStateService = mock(ReadStateService.class);
        memoryService = mock(MemoryService.class);
        thinkProcessService = mock(ThinkProcessService.class);

        // Strip the unrelated layers — the agent-doc / client-agent-doc
        // / RAG / settings paths are covered by other tests; we want to
        // assert exclusively on the ARCHIVED_CHAT replay.
        when(settingService.findByPrefixCascade(any(), any(), any(), any()))
                .thenReturn(Map.of());
        when(languageResolver.findChatLanguage(any(), any(), any(), any()))
                .thenReturn(null);
        when(languageResolver.findContentLanguage(any(), any(), any()))
                .thenReturn(null);

        org.springframework.beans.factory.ObjectProvider<
                de.mhus.vance.brain.rag.RagAutoInjectService> noRag =
                new org.springframework.beans.factory.ObjectProvider<>() {
                    @Override
                    public de.mhus.vance.brain.rag.RagAutoInjectService getObject() {
                        throw new UnsupportedOperationException();
                    }
                    @Override
                    public de.mhus.vance.brain.rag.RagAutoInjectService getObject(Object... args) {
                        throw new UnsupportedOperationException();
                    }
                    @Override
                    public de.mhus.vance.brain.rag.RagAutoInjectService getIfAvailable() {
                        return null;
                    }
                    @Override
                    public de.mhus.vance.brain.rag.RagAutoInjectService getIfUnique() {
                        return null;
                    }
                };
        loader = new MemoryContextLoader(
                settingService, sessionService, documentService,
                languageResolver, readStateService, noRag, memoryService,
                thinkProcessService);
    }

    @Test
    void noArchivedSummary_blockOmitsEarlierConversationHeading() {
        ThinkProcessDocument p = process();
        when(memoryService.activeByProcessAndKind(
                "acme", "proc-1", MemoryKind.ARCHIVED_CHAT))
                .thenReturn(List.of());

        String block = loader.composeBlock(p);

        // No layer fed anything → composeBlock returns null (its
        // documented "nothing to add" contract).
        assertThat(block).isNull();
    }

    @Test
    void singleActiveSummary_appendedUnderHeading() {
        ThinkProcessDocument p = process();
        MemoryDocument summary = archivedChat(
                "Compaction 2026-06-25T08:34:27", "User asked about X. Assistant solved Y.");
        when(memoryService.activeByProcessAndKind(
                "acme", "proc-1", MemoryKind.ARCHIVED_CHAT))
                .thenReturn(List.of(summary));

        String block = loader.composeBlock(p);

        assertThat(block)
                .contains("## Earlier Conversation (compacted)")
                .contains("User asked about X. Assistant solved Y.");
    }

    @Test
    void multipleActiveSummaries_picksNewestAsc() {
        // activeByProcessAndKind returns ASC by createdAt; in steady
        // state supersede() keeps only one row active, but if a race
        // produces two we want the newer one in the prompt.
        ThinkProcessDocument p = process();
        MemoryDocument older = archivedChat(
                "Compaction A", "Older summary text.");
        MemoryDocument newer = archivedChat(
                "Compaction B", "Newer summary text.");
        when(memoryService.activeByProcessAndKind(
                "acme", "proc-1", MemoryKind.ARCHIVED_CHAT))
                .thenReturn(List.of(older, newer));

        String block = loader.composeBlock(p);

        assertThat(block)
                .contains("Newer summary text.")
                .doesNotContain("Older summary text.");
    }

    @Test
    void blankSummary_skipped() {
        ThinkProcessDocument p = process();
        MemoryDocument blank = archivedChat("Compaction", "   ");
        when(memoryService.activeByProcessAndKind(
                "acme", "proc-1", MemoryKind.ARCHIVED_CHAT))
                .thenReturn(List.of(blank));

        String block = loader.composeBlock(p);

        // No usable content → no heading either. Block is null because
        // no other layer produced output in this test setup.
        assertThat(block).isNull();
    }

    @Test
    void missingTenantId_skips() {
        ThinkProcessDocument p = ThinkProcessDocument.builder()
                .id("proc-1")
                .sessionId("sess-42")
                .build();

        String block = loader.composeBlock(p);

        // No tenant → composeBlock short-circuits before any layer.
        assertThat(block).isNull();
    }

    @Test
    void missingProcessId_skipsArchivedChatLayer() {
        ThinkProcessDocument p = ThinkProcessDocument.builder()
                .tenantId("acme")
                .sessionId("sess-42")
                .build();

        loader.composeBlock(p);

        // The archived-chat layer must short-circuit on missing process-id;
        // verify it never queried the MemoryService at all. Other layers
        // are unwired in this test, so the block itself is null.
        org.mockito.Mockito.verifyNoInteractions(memoryService);
    }

    @Test
    void recompactionRow_excluded_bulkSlidingWindowSummaryStillRendered() {
        // A topic-recompaction ARCHIVED_CHAT (metadata.recompaction=true) is
        // also active but surfaces via its inline SYSTEM marker — it must NOT
        // hide the sliding-window bulk summary here (code-review Phase 2).
        ThinkProcessDocument p = process();
        MemoryDocument bulk = archivedChat("Compaction", "Bulk sliding-window history.");
        MemoryDocument recompaction = recompactionChat(
                "Recompaction auth", "Narrow topic: auth setup.");
        when(memoryService.activeByProcessAndKind(
                "acme", "proc-1", MemoryKind.ARCHIVED_CHAT))
                .thenReturn(List.of(bulk, recompaction)); // recompaction is newer

        String block = loader.composeBlock(p);

        assertThat(block)
                .contains("Bulk sliding-window history.")
                .doesNotContain("Narrow topic: auth setup.");
    }

    @Test
    void onlyRecompactionRow_blockOmitted() {
        ThinkProcessDocument p = process();
        MemoryDocument recompaction = recompactionChat(
                "Recompaction auth", "Narrow topic only.");
        when(memoryService.activeByProcessAndKind(
                "acme", "proc-1", MemoryKind.ARCHIVED_CHAT))
                .thenReturn(List.of(recompaction));

        // No non-recompaction bulk summary → the archived-chat block is
        // omitted (and no other layer produces output here) → null.
        assertThat(loader.composeBlock(p)).isNull();
    }

    // ─── helpers ────────────────────────────────────────────────────────

    private static ThinkProcessDocument process() {
        return ThinkProcessDocument.builder()
                .id("proc-1")
                .tenantId("acme")
                .sessionId("sess-42")
                .build();
    }

    private static MemoryDocument archivedChat(String title, String content) {
        return MemoryDocument.builder()
                .tenantId("acme")
                .sessionId("sess-42")
                .thinkProcessId("proc-1")
                .kind(MemoryKind.ARCHIVED_CHAT)
                .title(title)
                .content(content)
                .createdAt(Instant.now())
                .build();
    }

    private static MemoryDocument recompactionChat(String title, String content) {
        MemoryDocument m = archivedChat(title, content);
        m.setMetadata(Map.of("recompaction", true));
        return m;
    }
}
