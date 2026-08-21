package de.mhus.vance.shared.document.jaglan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.documents.MountAccess;
import de.mhus.vance.api.documents.WriterRole;
import de.mhus.vance.api.mount.MountedSource;
import de.mhus.vance.api.mount.MountedStat;
import de.mhus.vance.shared.document.DocumentDocument;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Shell-row lifecycle: stat on demand, cache for the declared TTL, keep the
 * last answer when the source is down, and mute a dead mount briefly.
 *
 * <p>See {@code planning/jaglan-mounted-docs.md} §6 and §8.
 */
class JaglanShellServiceTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "research";
    private static final String MOUNT = "library";
    private static final String DOC_PATH = "_ext/library/books/dune.pdf";
    private static final String IN_MOUNT = "books/dune.pdf";

    private MongoTemplate mongoTemplate;
    private JaglanPort port;
    private JaglanShellService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mongoTemplate = mock(MongoTemplate.class);
        port = mock(JaglanPort.class);
        ObjectProvider<JaglanPort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(port);
        service = new JaglanShellService(mongoTemplate, provider);
        when(port.mounts(TENANT, PROJECT)).thenReturn(List.of(source(MountAccess.RW)));
    }

    private static MountedSource source(MountAccess access) {
        return new MountedSource(
                MOUNT, "Book Library", "ode", access, null, null, Duration.ofMinutes(5), true);
    }

    private static MountedStat stat() {
        return new MountedStat(
                IN_MOUNT, false, 1234, "application/pdf", "etag-1", null, MountAccess.RW);
    }

    private static String docId() {
        return JaglanPaths.documentId(TENANT, PROJECT, MOUNT, IN_MOUNT);
    }

    /** A cached shell row with the given expiry. */
    private static DocumentDocument row(@org.jspecify.annotations.Nullable Instant expiresAt) {
        DocumentDocument doc = DocumentDocument.builder()
                .tenantId(TENANT).projectId(PROJECT)
                .path(DOC_PATH).name("dune.pdf")
                .mimeType("application/pdf").size(1234)
                .build();
        doc.setId(docId());
        doc.setMountFreshUntil(expiresAt);
        return doc;
    }

    private void mongoReturns(@org.jspecify.annotations.Nullable DocumentDocument doc) {
        when(mongoTemplate.findById(docId(), DocumentDocument.class)).thenReturn(doc);
    }

    // ─── cache hit / miss ───────────────────────────────────────────────

    @Test
    void resolve_freshRow_doesNotTouchTheSource() {
        mongoReturns(row(Instant.now().plusSeconds(120)));

        Optional<DocumentDocument> found = service.resolve(TENANT, PROJECT, DOC_PATH);

        assertThat(found).isPresent();
        verify(port, never()).stat(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void staleRow_isNotPurgedAndStillAnswers() {
        // The row is the only mapping from a derived id back to a path, and a
        // hash cannot be reversed — so a purged row breaks every id-keyed
        // endpoint with no way to recover. Freshness lives in
        // mountFreshUntil, which carries no TTL index; expiresAt must stay
        // untouched so Mongo's TTL monitor never sees these rows.
        mongoReturns(row(Instant.now().minusSeconds(1)));
        when(port.stat(TENANT, PROJECT, MOUNT, IN_MOUNT))
                .thenThrow(new JaglanUnavailableException(MOUNT, "down"));

        Optional<DocumentDocument> found = service.resolve(TENANT, PROJECT, DOC_PATH);

        assertThat(found).isPresent();
        assertThat(found.get().getExpiresAt()).isNull();
    }

    @Test
    void upsertedShell_marksADirectoryExplicitly() {
        // A mount folder needs its own row (an empty one has no children to be
        // derived from), so a listing must be able to tell it from a file —
        // and "no mime, size 0" is also what an empty text file looks like.
        mongoReturns(null);
        when(port.stat(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(MountedStat.directory("books")));

        service.resolve(TENANT, PROJECT, "_ext/library/books");

        assertThat(capturedShellUpdate().getUpdateObject()
                .get("$set", org.bson.Document.class).get("mountDirectory"))
                .isEqualTo(Boolean.TRUE);
    }

    @Test
    void directoryNamesIn_returnsOnlyTheFolderRows() {
        DocumentDocument folder = row(Instant.now().plusSeconds(60));
        folder.setPath("_ext/library/books");
        folder.setMountDirectory(true);
        DocumentDocument file = row(Instant.now().plusSeconds(60));
        file.setPath("_ext/library/readme.md");
        when(mongoTemplate.find(any(Query.class), eq(DocumentDocument.class)))
                .thenReturn(List.of(folder, file));

        assertThat(service.directoryNamesIn(TENANT, PROJECT, "_ext/library"))
                .containsExactly("books");
    }

    @Test
    void directoryNamesIn_outsideTheNamespace_isEmpty() {
        assertThat(service.directoryNamesIn(TENANT, PROJECT, "documents/notes")).isEmpty();
    }

    @Test
    void upsertedShell_setsFreshnessNotExpiry() {
        mongoReturns(null);
        when(port.stat(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(stat()));

        service.resolve(TENANT, PROJECT, DOC_PATH);

        String update = capturedShellUpdate().toString();
        assertThat(update).contains("mountFreshUntil");
        assertThat(update).doesNotContain("expiresAt");
    }

    @Test
    void resolve_expiredRow_statsAgain() {
        // Expired but not yet purged: Mongo's TTL monitor lags by up to a
        // minute, so the application has to compare the timestamp itself.
        mongoReturns(row(Instant.now().minusSeconds(1)));
        when(port.stat(TENANT, PROJECT, MOUNT, IN_MOUNT)).thenReturn(Optional.of(stat()));

        service.resolve(TENANT, PROJECT, DOC_PATH);

        verify(port).stat(TENANT, PROJECT, MOUNT, IN_MOUNT);
        verify(mongoTemplate).upsert(any(Query.class), any(Update.class),
                eq(DocumentDocument.class));
    }

    @Test
    void resolve_missingRow_statsAndUpserts() {
        mongoReturns(null);
        when(port.stat(TENANT, PROJECT, MOUNT, IN_MOUNT)).thenReturn(Optional.of(stat()));

        service.resolve(TENANT, PROJECT, DOC_PATH);

        verify(port).stat(TENANT, PROJECT, MOUNT, IN_MOUNT);
    }

    @Test
    void resolve_sourceSaysNotThere_dropsTheStaleRow() {
        mongoReturns(row(Instant.now().minusSeconds(1)));
        when(port.stat(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        Optional<DocumentDocument> found = service.resolve(TENANT, PROJECT, DOC_PATH);

        // The source answered — that is authoritative, unlike a failure.
        assertThat(found).isEmpty();
        verify(mongoTemplate).remove(any(Query.class), eq(DocumentDocument.class));
    }

    // ─── failure handling ───────────────────────────────────────────────

    @Test
    void resolve_sourceDown_keepsTheLastAnswer() {
        mongoReturns(row(Instant.now().minusSeconds(1)));
        when(port.stat(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new JaglanUnavailableException(MOUNT, "connect timeout"));

        Optional<DocumentDocument> found = service.resolve(TENANT, PROJECT, DOC_PATH);

        // Stale metadata beats "not found": a reader told the file is absent
        // concludes it does not exist.
        assertThat(found).isPresent();
        assertThat(found.get().getPath()).isEqualTo(DOC_PATH);
        verify(mongoTemplate, never()).remove(any(Query.class), eq(DocumentDocument.class));
    }

    @Test
    void resolve_sourceDown_withNothingCached_isEmpty() {
        mongoReturns(null);
        when(port.stat(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new JaglanUnavailableException(MOUNT, "connect timeout"));

        assertThat(service.resolve(TENANT, PROJECT, DOC_PATH)).isEmpty();
    }

    @Test
    void resolve_afterAFailure_doesNotRetryImmediately() {
        mongoReturns(row(Instant.now().minusSeconds(1)));
        when(port.stat(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new JaglanUnavailableException(MOUNT, "connect timeout"));

        service.resolve(TENANT, PROJECT, DOC_PATH);
        service.resolve(TENANT, PROJECT, DOC_PATH);
        service.resolve(TENANT, PROJECT, DOC_PATH);

        // Without the outage memory a dead mount pays its timeout on every
        // single call, which turns a broken mount into a slow application.
        verify(port, times(1)).stat(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void resolve_noPortAtAll_isEmptyRatherThanThrowing() {
        @SuppressWarnings("unchecked")
        ObjectProvider<JaglanPort> empty = mock(ObjectProvider.class);
        when(empty.getIfAvailable()).thenReturn(null);
        JaglanShellService bare = new JaglanShellService(mongoTemplate, empty);
        mongoReturns(null);

        assertThat(bare.resolve(TENANT, PROJECT, DOC_PATH)).isEmpty();
    }

    // ─── what the shell row carries ─────────────────────────────────────

    @Test
    void upsertedShell_neverCarriesAStorageId() {
        mongoReturns(null);
        when(port.stat(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(stat()));

        service.resolve(TENANT, PROJECT, DOC_PATH);

        String update = capturedShellUpdate().toString();
        // The absence of a storage handle is what marks the content as living
        // at the source — an accidental set here would strand a real blob.
        assertThat(update).contains("storageId");
        assertThat(update).contains("$unset");
    }

    @Test
    void upsertedShell_disablesSummaryAndRag() {
        mongoReturns(null);
        when(port.stat(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(stat()));

        service.resolve(TENANT, PROJECT, DOC_PATH);

        String update = capturedShellUpdate().toString();
        assertThat(update).contains("autoSummary");
        assertThat(update).contains("ragEnabled");
    }

    @Test
    void upsertedShell_readOnlySource_becomesASoftLockForEveryRole() {
        when(port.mounts(TENANT, PROJECT)).thenReturn(List.of(source(MountAccess.RO)));
        mongoReturns(null);
        when(port.stat(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(new MountedStat(
                        IN_MOUNT, false, 10, "application/pdf", null, null, MountAccess.RO)));

        service.resolve(TENANT, PROJECT, DOC_PATH);

        // Reusing the existing soft lock means every write surface already
        // refuses with a message, instead of each one needing a mount check.
        Object locked = capturedShellUpdate().getUpdateObject()
                .get("$set", org.bson.Document.class).get("lockedFor");
        assertThat(locked.toString())
                .contains(WriterRole.AI.name())
                .contains(WriterRole.USER.name())
                .contains(WriterRole.KIT.name());
    }

    @Test
    void upsertedShell_unknownAccessStaysWritable() {
        when(port.mounts(TENANT, PROJECT)).thenReturn(List.of(source(MountAccess.UNKNOWN)));
        mongoReturns(null);
        when(port.stat(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(new MountedStat(
                        IN_MOUNT, false, 10, "application/pdf", null, null, MountAccess.UNKNOWN)));

        service.resolve(TENANT, PROJECT, DOC_PATH);

        // Locking on UNKNOWN would turn a brief outage into a lock nobody can
        // explain; the source still refuses at write time if it must.
        Object locked = capturedShellUpdate().getUpdateObject()
                .get("$set", org.bson.Document.class).get("lockedFor");
        assertThat(locked.toString()).doesNotContain(WriterRole.USER.name());
    }

    @Test
    void resolve_fillsTheTransientAccessFromTheMount() {
        when(port.mounts(TENANT, PROJECT)).thenReturn(List.of(source(MountAccess.RO)));
        DocumentDocument cached = row(Instant.now().plusSeconds(120));
        mongoReturns(cached);

        Optional<DocumentDocument> found = service.resolve(TENANT, PROJECT, DOC_PATH);

        assertThat(found).isPresent();
        assertThat(found.get().getMountAccess()).isEqualTo(MountAccess.RO);
    }

    // ─── folder listing ─────────────────────────────────────────────────

    @Test
    void listFolder_freshMarker_doesNotTouchTheSource() {
        JaglanFolderState state = JaglanFolderState.builder()
                .tenantId(TENANT).projectId(PROJECT).mount(MOUNT).folder("books")
                .listedAt(Instant.now().minusSeconds(5))
                .expiresAt(Instant.now().plusSeconds(120))
                .entryCount(3)
                .build();
        when(mongoTemplate.findById(anyString(), eq(JaglanFolderState.class))).thenReturn(state);
        when(mongoTemplate.find(any(Query.class), eq(DocumentDocument.class)))
                .thenReturn(List.of());

        service.listFolder(TENANT, PROJECT, MOUNT, "books", false);

        verify(port, never()).list(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void listFolder_force_ignoresAFreshMarker() {
        JaglanFolderState state = JaglanFolderState.builder()
                .tenantId(TENANT).projectId(PROJECT).mount(MOUNT).folder("books")
                .listedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(120))
                .build();
        when(mongoTemplate.findById(anyString(), eq(JaglanFolderState.class))).thenReturn(state);
        when(mongoTemplate.find(any(Query.class), eq(DocumentDocument.class)))
                .thenReturn(List.of());
        when(port.list(TENANT, PROJECT, MOUNT, "books")).thenReturn(List.of(stat()));

        service.listFolder(TENANT, PROJECT, MOUNT, "books", true);

        verify(port).list(TENANT, PROJECT, MOUNT, "books");
    }

    @Test
    void listFolder_neverListed_goesToTheSource() {
        when(mongoTemplate.findById(anyString(), eq(JaglanFolderState.class))).thenReturn(null);
        when(mongoTemplate.find(any(Query.class), eq(DocumentDocument.class)))
                .thenReturn(List.of());
        when(port.list(TENANT, PROJECT, MOUNT, "")).thenReturn(List.of(stat()));

        service.listFolder(TENANT, PROJECT, MOUNT, "", false);

        verify(port).list(TENANT, PROJECT, MOUNT, "");
    }

    @Test
    void listFolder_emptyResult_isRecordedSoItIsNotAskedAgain() {
        // "Empty" and "never listed" are indistinguishable from the rows
        // alone — the marker is the only thing that tells them apart.
        when(mongoTemplate.findById(anyString(), eq(JaglanFolderState.class))).thenReturn(null);
        when(mongoTemplate.find(any(Query.class), eq(DocumentDocument.class)))
                .thenReturn(List.of());
        when(port.list(TENANT, PROJECT, MOUNT, "")).thenReturn(List.of());

        service.listFolder(TENANT, PROJECT, MOUNT, "", false);

        verify(mongoTemplate).upsert(any(Query.class), any(Update.class),
                eq(JaglanFolderState.class));
    }

    @Test
    void listFolder_vanishedEntries_arePruned() {
        when(mongoTemplate.findById(anyString(), eq(JaglanFolderState.class))).thenReturn(null);
        DocumentDocument gone = row(Instant.now().plusSeconds(60));
        gone.setId("ext_someoldid");
        gone.setPath("_ext/library/books/gone.pdf");
        when(mongoTemplate.find(any(Query.class), eq(DocumentDocument.class)))
                .thenReturn(List.of(gone));
        when(port.list(TENANT, PROJECT, MOUNT, "books")).thenReturn(List.of(stat()));

        service.listFolder(TENANT, PROJECT, MOUNT, "books", false);

        // A listing is authoritative for its own folder; without the prune a
        // deleted file resolves from its cached row until the TTL expires.
        verify(mongoTemplate).remove(any(Query.class), eq(DocumentDocument.class));
    }

    @Test
    void listFolder_sourceDown_keepsListedAtAndMutesTheMount() {
        when(mongoTemplate.findById(anyString(), eq(JaglanFolderState.class))).thenReturn(null);
        when(mongoTemplate.find(any(Query.class), eq(DocumentDocument.class)))
                .thenReturn(List.of());
        when(port.list(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new JaglanUnavailableException(MOUNT, "connect timeout"));

        service.listFolder(TENANT, PROJECT, MOUNT, "books", false);
        service.listFolder(TENANT, PROJECT, MOUNT, "books", false);

        verify(port, times(1)).list(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void isFolderKnown_distinguishesEmptyFromNeverListed() {
        when(mongoTemplate.findById(anyString(), eq(JaglanFolderState.class))).thenReturn(null);
        assertThat(service.isFolderKnown(TENANT, PROJECT, MOUNT, "books")).isFalse();

        JaglanFolderState listed = JaglanFolderState.builder()
                .listedAt(Instant.now()).entryCount(0).build();
        when(mongoTemplate.findById(anyString(), eq(JaglanFolderState.class))).thenReturn(listed);
        assertThat(service.isFolderKnown(TENANT, PROJECT, MOUNT, "books")).isTrue();
    }

    // ─── mounts + eviction ──────────────────────────────────────────────

    @Test
    void mounts_portFailure_isAnEmptyListNotAnException() {
        when(port.mounts(anyString(), anyString()))
                .thenThrow(new IllegalStateException("broken config"));

        // A folder tree must not fail to render because a mount is
        // misconfigured — that belongs in the status line, not in the listing.
        assertThat(service.mounts(TENANT, PROJECT)).isEmpty();
    }

    @Test
    void evictMount_removesRowsAndMarkers() {
        service.evictMount(TENANT, PROJECT, MOUNT);

        verify(mongoTemplate).remove(any(Query.class), eq(DocumentDocument.class));
        verify(mongoTemplate).remove(any(Query.class), eq(JaglanFolderState.class));
    }

    /** The {@code Update} handed to the shell upsert. */
    private Update capturedShellUpdate() {
        ArgumentCaptor<Update> captor = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).upsert(any(Query.class), captor.capture(),
                eq(DocumentDocument.class));
        return captor.getValue();
    }
}
