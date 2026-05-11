package de.mhus.vance.brain.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.thinkprocess.ReadStateEntry;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Pure-functional behaviour of {@link ReadStateService} plus the
 * forwarding to {@link ThinkProcessService} for the atomic writes.
 * The hot-path checks ({@code hasFresh}, {@code wasShown}) run against
 * the in-memory document snapshot so this test has no Mongo at all.
 */
class ReadStateServiceTest {

    private ThinkProcessService thinkProcessService;
    private ReadStateService service;

    @BeforeEach
    void setUp() {
        thinkProcessService = mock(ThinkProcessService.class);
        service = new ReadStateService(thinkProcessService);
    }

    // ─── hasFresh ───────────────────────────────────────────────────────

    @Test
    void hasFresh_emptyReadState_returnsFalse() {
        ThinkProcessDocument p = process("p-1");

        assertThat(service.hasFresh(p, "CLIENT_FILE:/x", "h1")).isFalse();
    }

    @Test
    void hasFresh_matchingKeyAndHash_returnsTrue() {
        ThinkProcessDocument p = process("p-1");
        p.getReadState().add(new ReadStateEntry(
                "CLIENT_FILE:/x", "h1", Instant.now(), false, null));

        assertThat(service.hasFresh(p, "CLIENT_FILE:/x", "h1")).isTrue();
    }

    @Test
    void hasFresh_keyPresentButHashChanged_returnsFalse() {
        // Stale entry — content changed on disk since the read.
        ThinkProcessDocument p = process("p-1");
        p.getReadState().add(new ReadStateEntry(
                "CLIENT_FILE:/x", "h-old", Instant.now(), false, null));

        assertThat(service.hasFresh(p, "CLIENT_FILE:/x", "h-new")).isFalse();
    }

    @Test
    void hasFresh_keyAbsent_returnsFalse() {
        ThinkProcessDocument p = process("p-1");
        p.getReadState().add(new ReadStateEntry(
                "CLIENT_FILE:/x", "h1", Instant.now(), false, null));

        assertThat(service.hasFresh(p, "DOCUMENT:65f", "h1")).isFalse();
    }

    @Test
    void hasFresh_nullInputs_returnFalseSafely() {
        ThinkProcessDocument p = process("p-1");
        assertThat(service.hasFresh(null, "k", "h")).isFalse();
        assertThat(service.hasFresh(p, null, "h")).isFalse();
        assertThat(service.hasFresh(p, "k", null)).isFalse();
    }

    // ─── recordRead ─────────────────────────────────────────────────────

    @Test
    void recordRead_blankInputs_skipMongo() {
        ThinkProcessDocument p = process("p-1");

        service.recordRead(p, "", "h", false, null);
        service.recordRead(p, "k", "", false, null);
        service.recordRead(null, "k", "h", false, null);

        verifyNoInteractions(thinkProcessService);
    }

    @Test
    void recordRead_forwardsToThinkProcessServiceWithBoundedSlice() {
        ThinkProcessDocument p = process("p-1");

        service.recordRead(p, "CLIENT_FILE:/Foo.java", "h1", false, 4096L);

        ArgumentCaptor<ReadStateEntry> entryCap =
                ArgumentCaptor.forClass(ReadStateEntry.class);
        ArgumentCaptor<Integer> maxCap = ArgumentCaptor.forClass(Integer.class);
        verify(thinkProcessService).appendReadStateEntry(
                eq("p-1"), entryCap.capture(), maxCap.capture());
        assertThat(entryCap.getValue().key()).isEqualTo("CLIENT_FILE:/Foo.java");
        assertThat(entryCap.getValue().contentHash()).isEqualTo("h1");
        assertThat(entryCap.getValue().partialView()).isFalse();
        assertThat(entryCap.getValue().bytesAtFetch()).isEqualTo(4096L);
        // The default cap from the plan — Claude Code matches this value.
        assertThat(maxCap.getValue())
                .isEqualTo(ReadStateService.DEFAULT_READ_STATE_MAX_ENTRIES);
    }

    @Test
    void recordRead_partialViewFlag_carriedThrough() {
        ThinkProcessDocument p = process("p-1");

        service.recordRead(p, "CLIENT_FILE:/CLAUDE.md", "h1",
                /*partialView*/ true, 1024L);

        ArgumentCaptor<ReadStateEntry> entryCap =
                ArgumentCaptor.forClass(ReadStateEntry.class);
        verify(thinkProcessService).appendReadStateEntry(
                eq("p-1"), entryCap.capture(), any(Integer.class));
        assertThat(entryCap.getValue().partialView()).isTrue();
    }

    // ─── wasShown / tryMarkShown ────────────────────────────────────────

    @Test
    void wasShown_emptyShownOnce_returnsFalse() {
        ThinkProcessDocument p = process("p-1");
        assertThat(service.wasShown(p, "CLAUDE.md")).isFalse();
    }

    @Test
    void wasShown_presentMarker_returnsTrue() {
        ThinkProcessDocument p = process("p-1");
        p.getShownOnce().add("CLAUDE.md");

        assertThat(service.wasShown(p, "CLAUDE.md")).isTrue();
        assertThat(service.wasShown(p, "kit-welcome")).isFalse();
    }

    @Test
    void tryMarkShown_firstAdd_forwardsAndReturnsTrue() {
        ThinkProcessDocument p = process("p-1");
        when(thinkProcessService.tryAddShownOnce(eq("p-1"), eq("CLAUDE.md")))
                .thenReturn(true);

        assertThat(service.tryMarkShown(p, "CLAUDE.md")).isTrue();
    }

    @Test
    void tryMarkShown_alreadyPresent_returnsFalse() {
        ThinkProcessDocument p = process("p-1");
        when(thinkProcessService.tryAddShownOnce(eq("p-1"), eq("CLAUDE.md")))
                .thenReturn(false);

        assertThat(service.tryMarkShown(p, "CLAUDE.md")).isFalse();
    }

    @Test
    void tryMarkShown_blankInputs_returnFalseWithoutForwarding() {
        ThinkProcessDocument p = process("p-1");

        assertThat(service.tryMarkShown(p, "")).isFalse();
        assertThat(service.tryMarkShown(p, null)).isFalse();
        assertThat(service.tryMarkShown(null, "X")).isFalse();
        // Process with no id is a fresh-built one — never reached Mongo.
        ThinkProcessDocument noId = new ThinkProcessDocument();
        assertThat(service.tryMarkShown(noId, "X")).isFalse();

        verifyNoInteractions(thinkProcessService);
    }

    // ─── clear ──────────────────────────────────────────────────────────

    @Test
    void clear_forwardsToThinkProcessService() {
        ThinkProcessDocument p = process("p-1");

        service.clear(p);

        verify(thinkProcessService).clearVolatileContextState("p-1");
    }

    @Test
    void clear_blankProcess_isNoOp() {
        service.clear(null);
        service.clear(new ThinkProcessDocument());
        verifyNoInteractions(thinkProcessService);
    }

    // ─── hashContent ────────────────────────────────────────────────────

    @Test
    void hashContent_stableAcrossCalls() {
        String a = ReadStateService.hashContent("Hello world");
        String b = ReadStateService.hashContent("Hello world");

        assertThat(a).isEqualTo(b).hasSize(64); // SHA-256 = 32 bytes = 64 hex chars
    }

    @Test
    void hashContent_differsOnDifferentContent() {
        String a = ReadStateService.hashContent("Hello world");
        String b = ReadStateService.hashContent("Hello world!");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void hashContent_blankInput_returnsEmpty() {
        assertThat(ReadStateService.hashContent(null)).isEmpty();
        assertThat(ReadStateService.hashContent("")).isEmpty();
    }

    // ─── helpers ────────────────────────────────────────────────────────

    private static ThinkProcessDocument process(String id) {
        return ThinkProcessDocument.builder()
                .id(id)
                .tenantId("acme")
                .projectId("proj")
                .sessionId("sess")
                .readState(new ArrayList<>())
                .shownOnce(new LinkedHashSet<>())
                .build();
    }

    /** Unused suppress import for the {@link List} type used by mocks. */
    @SuppressWarnings("unused")
    private static List<ReadStateEntry> listType() { return null; }
}
