package de.mhus.vance.brain.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Worker-Spawn-Kontext in {@link MemoryContextLoader}: when a process
 * has {@code parentProcessId != null}, the prompt block surfaces a
 * Parent-Context section. Same-project workers get the parent's
 * ARCHIVED_CHAT summary attached; cross-project workers see only the
 * parent identity (confidentiality boundary).
 *
 * <p>See {@code planning/memory-compaction.md} §6.
 */
class MemoryContextLoaderParentContextTest {

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

        // Strip unrelated layers — the agent-doc / client-agent-doc /
        // RAG / settings paths are covered by other tests; we assert
        // exclusively on the Parent-Context surface.
        when(settingService.findByPrefixCascade(any(), any(), any(), any()))
                .thenReturn(Map.of());
        when(languageResolver.findChatLanguage(any(), any(), any(), any()))
                .thenReturn(null);
        when(languageResolver.findContentLanguage(any(), any(), any()))
                .thenReturn(null);
        // Own-process archived-chat: empty (we're testing parent, not self)
        when(memoryService.activeByProcessAndKind(
                eq("acme"), eq("worker-1"), eq(MemoryKind.ARCHIVED_CHAT)))
                .thenReturn(List.of());

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
    void noParentProcessId_skipsBlock_andSkipsLookup() {
        ThinkProcessDocument p = process("worker-1", "proj-1", /*parent*/ null);

        String block = loader.composeBlock(p);

        assertThat(block).isNull();
        verify(thinkProcessService, never()).findById(anyString());
    }

    @Test
    void sameProject_withoutParentSummary_emitsIdentityOnly() {
        ThinkProcessDocument worker = process("worker-1", "proj-1", "parent-1");
        ThinkProcessDocument parent = parent("parent-1", "proj-1",
                "arthur-main", "default", "arthur", "Implement payments");
        when(thinkProcessService.findById("parent-1"))
                .thenReturn(Optional.of(parent));
        when(memoryService.activeByProcessAndKind(
                "acme", "parent-1", MemoryKind.ARCHIVED_CHAT))
                .thenReturn(List.of());

        String block = loader.composeBlock(worker);

        assertThat(block)
                .contains("## Parent Context")
                .contains("`arthur-main`")
                .contains("recipe: default")
                .contains("engine: arthur")
                .contains("Parent mission: Implement payments")
                .doesNotContain("Parent Conversation Summary");
    }

    @Test
    void sameProject_withParentSummary_emitsIdentityPlusSummary() {
        ThinkProcessDocument worker = process("worker-1", "proj-1", "parent-1");
        ThinkProcessDocument parent = parent("parent-1", "proj-1",
                "arthur-main", "default", "arthur", "Implement payments");
        when(thinkProcessService.findById("parent-1"))
                .thenReturn(Optional.of(parent));
        MemoryDocument summary = archivedChat("acme", "parent-1",
                "User asked about stripe integration. We decided on payment-intents.");
        when(memoryService.activeByProcessAndKind(
                "acme", "parent-1", MemoryKind.ARCHIVED_CHAT))
                .thenReturn(List.of(summary));

        String block = loader.composeBlock(worker);

        assertThat(block)
                .contains("## Parent Context")
                .contains("### Parent Conversation Summary")
                .contains("payment-intents");
    }

    @Test
    void crossProject_emitsIdentityOnly_neverQueriesParentSummary() {
        ThinkProcessDocument worker = process("worker-1", "proj-2", "parent-1");
        ThinkProcessDocument parent = parent("parent-1", "proj-1",
                "trillian-control", "trillian-control", "trillian-control",
                "Cross-project orchestration");
        when(thinkProcessService.findById("parent-1"))
                .thenReturn(Optional.of(parent));

        String block = loader.composeBlock(worker);

        assertThat(block)
                .contains("## Parent Context")
                .contains("`trillian-control`")
                .contains("Cross-project orchestration")
                .doesNotContain("Parent Conversation Summary");
        // Critical: confidentiality boundary — the parent's summary must
        // never be queried when projects differ, even if a summary exists.
        verify(memoryService, never()).activeByProcessAndKind(
                anyString(), eq("parent-1"), eq(MemoryKind.ARCHIVED_CHAT));
    }

    @Test
    void parentNotFound_gracefullySkipsWholeBlock() {
        ThinkProcessDocument worker = process("worker-1", "proj-1", "ghost");
        when(thinkProcessService.findById("ghost"))
                .thenReturn(Optional.empty());

        String block = loader.composeBlock(worker);

        // Block is null because no other layer added content, and the
        // parent path skipped cleanly without throwing.
        assertThat(block).isNull();
    }

    @Test
    void parentLookupThrows_swallowedAndBlockBuildContinues() {
        ThinkProcessDocument worker = process("worker-1", "proj-1", "parent-1");
        when(thinkProcessService.findById("parent-1"))
                .thenThrow(new RuntimeException("mongo down"));

        String block = loader.composeBlock(worker);

        // Failure is swallowed at the parent-context layer; the rest of
        // composeBlock is unaffected. No other layer contributed, so the
        // overall return is null.
        assertThat(block).isNull();
    }

    @Test
    void parentMissingName_rendersQuestionMarkPlaceholder() {
        ThinkProcessDocument worker = process("worker-1", "proj-1", "parent-1");
        ThinkProcessDocument parent = parent("parent-1", "proj-1",
                /*name*/ null, /*recipe*/ null, /*engine*/ null, /*title*/ null);
        when(thinkProcessService.findById("parent-1"))
                .thenReturn(Optional.of(parent));
        when(memoryService.activeByProcessAndKind(
                "acme", "parent-1", MemoryKind.ARCHIVED_CHAT))
                .thenReturn(List.of());

        String block = loader.composeBlock(worker);

        // Defensive placeholders — never a NullPointerException or "null"
        // in the rendered prompt.
        assertThat(block)
                .contains("## Parent Context")
                .contains("`?`")
                .contains("recipe: ?")
                .contains("engine: ?")
                .doesNotContain("null");
    }

    // ─── helpers ────────────────────────────────────────────────────────

    private static ThinkProcessDocument process(
            String id, String projectId,
            @org.jspecify.annotations.Nullable String parentProcessId) {
        return ThinkProcessDocument.builder()
                .id(id)
                .tenantId("acme")
                .projectId(projectId)
                .sessionId("sess-42")
                .parentProcessId(parentProcessId)
                .build();
    }

    private static ThinkProcessDocument parent(
            String id, String projectId,
            @org.jspecify.annotations.Nullable String name,
            @org.jspecify.annotations.Nullable String recipe,
            @org.jspecify.annotations.Nullable String engine,
            @org.jspecify.annotations.Nullable String title) {
        return ThinkProcessDocument.builder()
                .id(id)
                .tenantId("acme")
                .projectId(projectId)
                .sessionId("parent-sess")
                .name(name)
                .recipeName(recipe)
                .thinkEngine(engine)
                .title(title)
                .build();
    }

    private static MemoryDocument archivedChat(
            String tenantId, String processId, String content) {
        return MemoryDocument.builder()
                .tenantId(tenantId)
                .thinkProcessId(processId)
                .kind(MemoryKind.ARCHIVED_CHAT)
                .title("Compaction")
                .content(content)
                .createdAt(Instant.now())
                .build();
    }
}
