package de.mhus.vance.shared.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.documents.MountSearchOutcome;
import de.mhus.vance.shared.document.jaglan.JaglanShellService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.storage.StorageService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * A search issued inside a mounted folder is handed to the source.
 *
 * <p>The defect this closes: the folder listing's search is a Mongo query over
 * the rows it has, and inside a mount those are only the entries somebody
 * browsed to — so it answered "0 results" for a source holding tens of
 * thousands of matches, with no sign that it had asked the wrong question.
 */
class DocumentServiceMountSearchTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "research";

    private MongoTemplate mongoTemplate;
    private JaglanShellService shellService;
    private DocumentService service;

    @BeforeEach
    void setUp() {
        DocumentRepository repository = mock(DocumentRepository.class);
        StorageService storageService = mock(StorageService.class);
        mongoTemplate = mock(MongoTemplate.class);
        ResourcePatternResolver resourcePatternResolver = mock(ResourcePatternResolver.class);
        DocumentHeaderParser headerParser = mock(DocumentHeaderParser.class);
        DocumentArchiveService archiveService = mock(DocumentArchiveService.class);
        SettingService settingService = mock(SettingService.class);
        service = new DocumentService(
                repository, storageService, mongoTemplate,
                resourcePatternResolver, headerParser,
                archiveService, settingService, DocTestSupport.permissionProvider());
        shellService = mock(JaglanShellService.class);
        ReflectionTestUtils.setField(service, "shellService", shellService);
    }

    /**
     * Stub the folder-name aggregation the non-delegated path runs. Only the
     * tests that expect the ordinary listing need it — a delegated search must
     * never get this far, which is what {@code verify(never())} asserts.
     */
    @SuppressWarnings("unchecked")
    private void mongoAggregationYieldsNothing() {
        com.mongodb.client.MongoCollection<org.bson.Document> collection =
                mock(com.mongodb.client.MongoCollection.class);
        com.mongodb.client.AggregateIterable<org.bson.Document> iterable =
                mock(com.mongodb.client.AggregateIterable.class);
        when(mongoTemplate.getCollection(anyString())).thenReturn(collection);
        when(collection.aggregate(any())).thenReturn(iterable);
        when(iterable.allowDiskUse(any())).thenReturn(iterable);
        when(iterable.iterator()).thenReturn(mock(com.mongodb.client.MongoCursor.class));
    }

    private static DocumentDocument hit(String path) {
        DocumentDocument doc = DocumentDocument.builder()
                .tenantId(TENANT).projectId(PROJECT).path(path).name("dune.md")
                .mimeType("text/markdown").size(1372)
                .build();
        doc.setId("ext_hit");
        return doc;
    }

    private void sourceAnswers(MountSearchOutcome outcome, DocumentDocument... hits) {
        when(shellService.searchInMount(anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(new JaglanShellService.MountSearch(List.of(hits), outcome));
    }

    // ─── delegation ─────────────────────────────────────────────────────

    @Test
    void searchInsideAMount_goesToTheSourceNotToMongo() {
        sourceAnswers(MountSearchOutcome.DELEGATED, hit("_ext/hrafnagud/article/2026/x.md"));

        DocumentService.FolderListing listing = service.listByFolder(
                TENANT, PROJECT, "_ext/hrafnagud", "trump", 0, 50);

        assertThat(listing.mountSearch()).isEqualTo(MountSearchOutcome.DELEGATED);
        assertThat(listing.files()).hasSize(1);
        verify(shellService).searchInMount(TENANT, PROJECT, "hrafnagud", "trump", 50);
        // The Mongo aggregation that used to answer this must not run.
        verify(mongoTemplate, never()).find(any(Query.class), eq(DocumentDocument.class));
    }

    @Test
    void searchInsideADeepMountFolder_stillAsksTheWholeMount() {
        // Narrowing the source's answer to the browsed subtree would turn a
        // useful result into an empty one — a file seven levels down is what
        // somebody searches for rather than browses to.
        sourceAnswers(MountSearchOutcome.DELEGATED, hit("_ext/hrafnagud/article/2026/08/x.md"));

        service.listByFolder(TENANT, PROJECT, "_ext/hrafnagud/article/2026/08/21", "trump", 0, 50);

        verify(shellService).searchInMount(TENANT, PROJECT, "hrafnagud", "trump", 50);
    }

    @Test
    void delegatedSearch_returnsNoFolders() {
        // A hit list is not a folder view; mixing in the browsed directory's
        // subfolders would suggest the hits came from there.
        sourceAnswers(MountSearchOutcome.DELEGATED, hit("_ext/hrafnagud/a.md"));

        assertThat(service.listByFolder(TENANT, PROJECT, "_ext/hrafnagud", "trump", 0, 50)
                .folders()).isEmpty();
    }

    @Test
    void delegatedSearch_laterPagesAreEmptyRatherThanPretending() {
        // The contract has no cursor, so there is no second page to fetch.
        DocumentService.FolderListing page2 = service.listByFolder(
                TENANT, PROJECT, "_ext/hrafnagud", "trump", 1, 50);

        assertThat(page2.files()).isEmpty();
        assertThat(page2.mountSearch()).isEqualTo(MountSearchOutcome.DELEGATED);
        verify(shellService, never())
                .searchInMount(anyString(), anyString(), anyString(), anyString(), anyInt());
    }

    // ─── the outcomes that are not answers ──────────────────────────────

    @Test
    void sourceCannotSearch_saysSoInsteadOfReturningNothing() {
        sourceAnswers(MountSearchOutcome.UNSUPPORTED);

        DocumentService.FolderListing listing = service.listByFolder(
                TENANT, PROJECT, "_ext/library", "dune", 0, 50);

        // Empty plus UNSUPPORTED is "nobody looked", not "not there".
        assertThat(listing.files()).isEmpty();
        assertThat(listing.mountSearch()).isEqualTo(MountSearchOutcome.UNSUPPORTED);
    }

    @Test
    void sourceUnreachable_isDistinctFromCannotSearch() {
        sourceAnswers(MountSearchOutcome.UNAVAILABLE);

        assertThat(service.listByFolder(TENANT, PROJECT, "_ext/library", "dune", 0, 50)
                .mountSearch()).isEqualTo(MountSearchOutcome.UNAVAILABLE);
    }

    // ─── everything else is unchanged ───────────────────────────────────

    @Test
    void searchInTheNamespaceRoot_delegatesToNobody() {
        // `_ext/` names no mount; there is nothing there but the synthetic
        // folders.
        DocumentService.FolderListing listing =
                service.listByFolder(TENANT, PROJECT, "_ext", "dune", 0, 50);

        assertThat(listing.mountSearch()).isNull();
        verify(shellService, never())
                .searchInMount(anyString(), anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void searchOutsideAMount_isUntouched() {
        mongoAggregationYieldsNothing();
        when(mongoTemplate.find(any(Query.class), eq(DocumentDocument.class)))
                .thenReturn(List.of());

        DocumentService.FolderListing listing =
                service.listByFolder(TENANT, PROJECT, "documents", "dune", 0, 50);

        assertThat(listing.mountSearch()).isNull();
        verify(shellService, never())
                .searchInMount(anyString(), anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void browsingAMountWithoutASearchTerm_isNotADelegatedSearch() {
        mongoAggregationYieldsNothing();
        when(mongoTemplate.find(any(Query.class), eq(DocumentDocument.class)))
                .thenReturn(List.of());
        when(shellService.directoryNamesIn(anyString(), anyString(), anyString()))
                .thenReturn(List.of());

        DocumentService.FolderListing listing =
                service.listByFolder(TENANT, PROJECT, "_ext/hrafnagud", null, 0, 50);

        assertThat(listing.mountSearch()).isNull();
        verify(shellService, never())
                .searchInMount(anyString(), anyString(), anyString(), anyString(), anyInt());
    }
}
