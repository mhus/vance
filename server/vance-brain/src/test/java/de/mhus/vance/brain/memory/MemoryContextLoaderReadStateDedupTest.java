package de.mhus.vance.brain.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.context.ReadStateService;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.settings.LanguageResolver;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Read-state-aware auto-attachment dedup in {@link MemoryContextLoader}:
 * a second turn with the same agent-doc / client-agent-doc content
 * gets a one-line stub instead of the full body. Save tokens, keep the
 * prompt-cache marker stable. See {@code planning/brain-context-assembler.md}
 * §5.2.
 */
class MemoryContextLoaderReadStateDedupTest {

    private SettingService settingService;
    private SessionService sessionService;
    private DocumentService documentService;
    private LanguageResolver languageResolver;
    private ReadStateService readStateService;
    private MemoryContextLoader loader;

    @BeforeEach
    void setUp() {
        settingService = mock(SettingService.class);
        sessionService = mock(SessionService.class);
        documentService = mock(DocumentService.class);
        languageResolver = mock(LanguageResolver.class);
        readStateService = mock(ReadStateService.class);
        loader = new MemoryContextLoader(
                settingService, sessionService, documentService,
                languageResolver, readStateService);

        // Empty defaults — only the bits each test sets are populated.
        when(settingService.findByPrefixCascade(any(), any(), any(), any()))
                .thenReturn(Map.of());
        when(languageResolver.findChatLanguage(any(), any(), any(), any()))
                .thenReturn(null);
        when(languageResolver.findContentLanguage(any(), any(), any()))
                .thenReturn(null);
    }

    // ─── Agent doc (cascade) ────────────────────────────────────────────

    @Test
    void agentDoc_firstSeen_inlinesFullContent_andRecordsRead() {
        ThinkProcessDocument p = process();
        bindSessionToProject(p, "proj-1");
        String body = "# Agent rules\nDo helpful work.";
        when(documentService.lookupCascade("acme", "proj-1", "agent.md"))
                .thenReturn(Optional.of(new LookupResult(
                        "agent.md", body, LookupResult.Source.PROJECT, null)));
        // First turn: cache miss.
        when(readStateService.hasFresh(eq(p), anyString(), anyString()))
                .thenReturn(false);

        String block = loader.composeBlock(p);

        assertThat(block).contains("## Agent Notes (agent.md)")
                         .contains("Do helpful work")
                         .doesNotContain(MemoryContextLoader.DEDUP_AGENT_STUB);
        verify(readStateService).recordRead(
                eq(p),
                eq(MemoryContextLoader.AGENT_DOC_KEY_PREFIX + "proj-1:agent.md"),
                anyString(),
                anyBoolean(),
                anyLong());
    }

    @Test
    void agentDoc_secondTurn_sameHash_dedupsToStub_andSkipsRecord() {
        ThinkProcessDocument p = process();
        bindSessionToProject(p, "proj-1");
        String body = "# Agent rules\nDo helpful work.";
        when(documentService.lookupCascade("acme", "proj-1", "agent.md"))
                .thenReturn(Optional.of(new LookupResult(
                        "agent.md", body, LookupResult.Source.PROJECT, null)));
        // Same hash already in read-state.
        when(readStateService.hasFresh(eq(p), anyString(), anyString()))
                .thenReturn(true);

        String block = loader.composeBlock(p);

        assertThat(block).contains("## Agent Notes (agent.md)")
                         .contains(MemoryContextLoader.DEDUP_AGENT_STUB)
                         .doesNotContain("Do helpful work");
        // Crucially: NO recordRead on the dedup path — that would
        // just push duplicates into the LRU.
        verify(readStateService, never()).recordRead(
                any(), any(), any(), anyBoolean(), anyLong());
    }

    @Test
    void agentDoc_changedContent_inlinesFullAgain_andRecordsNewHash() {
        // Plain happy-path: hashContent will produce a different hash
        // for changed content, and readStateService will return false.
        // (The test verifies the wiring: when hasFresh is false, full
        // body is inlined and recordRead fires.)
        ThinkProcessDocument p = process();
        bindSessionToProject(p, "proj-1");
        String newBody = "# Agent rules v2\nNew instructions.";
        when(documentService.lookupCascade("acme", "proj-1", "agent.md"))
                .thenReturn(Optional.of(new LookupResult(
                        "agent.md", newBody, LookupResult.Source.PROJECT, null)));
        when(readStateService.hasFresh(eq(p), anyString(), anyString()))
                .thenReturn(false);

        String block = loader.composeBlock(p);

        assertThat(block).contains("New instructions");
        verify(readStateService).recordRead(
                eq(p), anyString(), anyString(), anyBoolean(), anyLong());
    }

    @Test
    void agentDoc_emptyContent_rendersHeadingOnly_noRecordRead() {
        ThinkProcessDocument p = process();
        bindSessionToProject(p, "proj-1");
        when(documentService.lookupCascade("acme", "proj-1", "agent.md"))
                .thenReturn(Optional.of(new LookupResult(
                        "agent.md", "", LookupResult.Source.PROJECT, null)));

        String block = loader.composeBlock(p);

        assertThat(block).contains("## Agent Notes");
        verify(readStateService, never()).recordRead(
                any(), any(), any(), anyBoolean(), anyLong());
        verify(readStateService, never()).hasFresh(any(), any(), any());
    }

    @Test
    void agentDoc_keyUsesProjectAndPath() {
        ThinkProcessDocument p = process();
        bindSessionToProject(p, "team-alpha");
        when(documentService.lookupCascade("acme", "team-alpha", "notes/agent.md"))
                .thenReturn(Optional.of(new LookupResult(
                        "notes/agent.md", "content", LookupResult.Source.PROJECT, null)));
        when(readStateService.hasFresh(eq(p), anyString(), anyString()))
                .thenReturn(false);
        // Pin agentDocument param to the nested path.
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(MemoryContextLoader.AGENT_DOC_PARAM, "notes/agent.md");
        p.setEngineParams(params);

        loader.composeBlock(p);

        verify(readStateService).recordRead(
                eq(p),
                eq(MemoryContextLoader.AGENT_DOC_KEY_PREFIX + "team-alpha:notes/agent.md"),
                anyString(), anyBoolean(), anyLong());
    }

    // ─── Client agent doc (session-uploaded) ────────────────────────────

    @Test
    void clientAgentDoc_firstSeen_inlinesAndRecords() {
        ThinkProcessDocument p = process();
        enableClientAgentDoc(p);
        bindSession(p);
        SessionDocument session = sessionWithClientDoc(
                "sess-42", "/home/user/foo/CLAUDE.md", "# Local rules\nstay terse.");
        when(sessionService.findBySessionId("sess-42"))
                .thenReturn(Optional.of(session));
        when(readStateService.hasFresh(eq(p), anyString(), anyString()))
                .thenReturn(false);

        String block = loader.composeBlock(p);

        assertThat(block).contains("from client: /home/user/foo/CLAUDE.md")
                         .contains("stay terse")
                         .doesNotContain(MemoryContextLoader.DEDUP_CLIENT_AGENT_STUB);
        verify(readStateService).recordRead(
                eq(p),
                eq(MemoryContextLoader.CLIENT_AGENT_DOC_KEY_PREFIX + "sess-42"),
                anyString(), anyBoolean(), anyLong());
    }

    @Test
    void clientAgentDoc_secondTurn_sameHash_dedupsToStub() {
        ThinkProcessDocument p = process();
        enableClientAgentDoc(p);
        bindSession(p);
        SessionDocument session = sessionWithClientDoc(
                "sess-42", "/home/user/foo/CLAUDE.md", "# Local rules\nstay terse.");
        when(sessionService.findBySessionId("sess-42"))
                .thenReturn(Optional.of(session));
        when(readStateService.hasFresh(eq(p), anyString(), anyString()))
                .thenReturn(true);

        String block = loader.composeBlock(p);

        assertThat(block).contains("from client:")
                         .contains(MemoryContextLoader.DEDUP_CLIENT_AGENT_STUB)
                         .doesNotContain("stay terse");
        verify(readStateService, never()).recordRead(
                any(), any(), any(), anyBoolean(), anyLong());
    }

    @Test
    void clientAgentDoc_disabledByParam_skipsReadStateCheck() {
        ThinkProcessDocument p = process();
        // useClientAgentDoc NOT set → flag is false.
        bindSession(p);

        loader.composeBlock(p);

        // The session lookup IS made (by the language-resolver path);
        // what must NOT happen is the read-state check for the client
        // agent doc. Without that gate, every disabled-flag turn would
        // burn a Mongo round-trip on the agent-doc dedup lookup.
        verify(readStateService, never()).hasFresh(any(), any(), any());
    }

    @Test
    void recordReadFailure_doesNotBreakPromptBuild() {
        ThinkProcessDocument p = process();
        bindSessionToProject(p, "proj-1");
        when(documentService.lookupCascade("acme", "proj-1", "agent.md"))
                .thenReturn(Optional.of(new LookupResult(
                        "agent.md", "content", LookupResult.Source.PROJECT, null)));
        when(readStateService.hasFresh(eq(p), anyString(), anyString()))
                .thenReturn(false);
        // Pathological: recordRead throws. The prompt must still build.
        org.mockito.Mockito.doThrow(new RuntimeException("mongo down"))
                .when(readStateService).recordRead(any(), any(), any(), anyBoolean(), anyLong());

        String block = loader.composeBlock(p);

        // Full content was still inlined this turn — the failure was on
        // the side-channel write, not the prompt path.
        assertThat(block).contains("content");
    }

    // ─── helpers ────────────────────────────────────────────────────────

    private static ThinkProcessDocument process() {
        return ThinkProcessDocument.builder()
                .id("proc-1")
                .tenantId("acme")
                .sessionId("sess-42")
                .build();
    }

    private void bindSession(ThinkProcessDocument p) {
        when(sessionService.findBySessionId("sess-42"))
                .thenReturn(Optional.of(SessionDocument.builder()
                        .sessionId("sess-42")
                        .projectId("proj-1")
                        .build()));
    }

    private void bindSessionToProject(ThinkProcessDocument p, String projectId) {
        when(sessionService.findBySessionId(p.getSessionId()))
                .thenReturn(Optional.of(SessionDocument.builder()
                        .sessionId(p.getSessionId())
                        .projectId(projectId)
                        .build()));
    }

    private static void enableClientAgentDoc(ThinkProcessDocument p) {
        Map<String, Object> params = new LinkedHashMap<>(
                p.getEngineParams() == null ? Map.of() : p.getEngineParams());
        params.put(MemoryContextLoader.USE_CLIENT_AGENT_DOC_PARAM, true);
        p.setEngineParams(params);
    }

    private static SessionDocument sessionWithClientDoc(
            String sessionId, String path, String content) {
        return SessionDocument.builder()
                .sessionId(sessionId)
                .projectId("proj-1")
                .clientAgentDocPath(path)
                .clientAgentDoc(content)
                .build();
    }
}
