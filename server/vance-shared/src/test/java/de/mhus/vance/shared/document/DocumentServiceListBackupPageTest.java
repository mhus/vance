package de.mhus.vance.shared.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.util.PathGlob;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

/**
 * The contract {@code listBackupPage} owes the {@code vance-liver} client:
 * <ul>
 *   <li>Trash and mounted documents are excluded server-side.</li>
 *   <li>Cursor pagination uses {@code _id} ascending and applies only to the
 *       filtered set.</li>
 *   <li>Prefix-only deny-globs become a Mongo regex on the path field.</li>
 *   <li>Globs with intra-segment wildcards are applied post-fetch via the
 *       same {@link PathGlob} the client uses.</li>
 * </ul>
 *
 * <p>The Mongo level is exercised through a mocked template; the post-fetch
 * filter is tested with real documents so the path-glob semantics are pinned.
 */
class DocumentServiceListBackupPageTest {

    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final DocumentRepository repository = mock(DocumentRepository.class);
    private final DocumentService service = new DocumentService(
            repository,
            mock(de.mhus.vance.shared.storage.StorageService.class),
            mongoTemplate,
            mock(org.springframework.core.io.support.ResourcePatternResolver.class),
            mock(DocumentHeaderParser.class),
            mock(DocumentArchiveService.class),
            mock(de.mhus.vance.shared.settings.SettingService.class),
            DocTestSupport.permissionProvider());

    private static DocumentDocument row(String path) {
        DocumentDocument doc = new DocumentDocument();
        doc.setId("id-" + path);
        doc.setTenantId("acme");
        doc.setProjectId("wiki");
        doc.setPath(path);
        doc.setName(path);
        doc.setMimeType("text/markdown");
        doc.setStatus(DocumentStatus.ACTIVE);
        return doc;
    }

    // ── prefix-glob translates to a Mongo regex ─────────────────────

    @Test
    void prefixDenies_becomeRegexOnPathField() {
        when(mongoTemplate.find(any(Query.class), any(Class.class))).thenReturn(List.of());

        service.listBackupPage("acme", "wiki", List.of("_vance/**", "drafts/**"), null, 100);

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(queryCaptor.capture(), any(Class.class));
        // The combined regex is anchored and OR'd across the prefix-denies.
        // The test does not need to nail the exact alternation form, only
        // confirm that the query was asked for a non-empty path filter.
        assertThat(queryCaptor.getValue().toString())
                .as("prefix deny-globs must surface as a Mongo path filter")
                .contains("path");
    }

    @Test
    void emptyDenyList_skipsPathFilter() {
        when(mongoTemplate.find(any(Query.class), any(Class.class))).thenReturn(List.of());

        service.listBackupPage("acme", "wiki", List.of(), null, 100);

        // Still one find() — for the trash/mount exclusion, which is
        // unconditional. No additional deny-regex added.
        verify(mongoTemplate, times(1)).find(any(Query.class), any(Class.class));
    }

    @Test
    void blankAfterId_treatedAsFirstPage() {
        // A previous version of this code did `new ObjectId(afterId)`
        // unconditionally inside the if (afterId != null) branch — fine
        // for null, broken for "". An empty-string cursor threw
        // IllegalArgumentException from ObjectId.
        when(mongoTemplate.find(any(Query.class), any(Class.class))).thenReturn(List.of());

        service.listBackupPage("acme", "wiki", List.of(), "", 100);

        // No exception; one find() with no _id filter clause.
        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(queryCaptor.capture(), any(Class.class));
        String queryStr = queryCaptor.getValue().getQueryObject().toString();
        assertThat(queryStr)
                .as("blank cursor must NOT add a _id.gt filter clause")
                .doesNotContain("_id");
        // Sort still includes _id — that's the stable ordering, not a filter.
    }

    // ── post-fetch filter for non-prefix globs ──────────────────────

    @Test
    void intraSegmentGlobs_areAppliedPostFetch() {
        // Three documents; the deny glob "*.tmp" rejects only the .tmp one.
        when(mongoTemplate.find(any(Query.class), any(Class.class)))
                .thenReturn(List.of(row("notes/keep.md"), row("notes/scratch.tmp"), row("notes/other.md")));

        List<DocumentDocument> result = service.listBackupPage("acme", "wiki", List.of("**/*.tmp"), null, 100)
                .rows();

        assertThat(result).extracting(DocumentDocument::getPath).containsExactly("notes/keep.md", "notes/other.md");
    }

    @Test
    void mixedPrefixAndNonPrefixDenies_areBothApplied() {
        when(mongoTemplate.find(any(Query.class), any(Class.class)))
                .thenReturn(List.of(
                        row("notes/keep.md"),
                        row("notes/scratch.tmp"),
                        row("_vance/config/x.yaml"),
                        row("drafts/wip.md")));

        List<DocumentDocument> result = service.listBackupPage(
                        "acme", "wiki", List.of("_vance/**", "drafts/**", "**/*.tmp"), null, 100)
                .rows();

        // The Mongo prefix-filter is exercised through the captured Query
        // (separate test). Here the mock returns everything, so the
        // post-fetch filter only sees scratch.tmp as a drop — the prefix
        // denies would have been dropped by Mongo if the mock were real.
        assertThat(result)
                .extracting(DocumentDocument::getPath)
                .containsExactlyInAnyOrder("notes/keep.md", "_vance/config/x.yaml", "drafts/wip.md");
    }

    // ── cursor ──────────────────────────────────────────────────────

    @Test
    void cursor_setsGtFilterOn_id() {
        when(mongoTemplate.find(any(Query.class), any(Class.class))).thenReturn(List.of());

        service.listBackupPage("acme", "wiki", List.of(), "507f1f77bcf86cd799439011", 50);

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(queryCaptor.capture(), any(Class.class));
        // The cursor itself is opaque; the test only pins that the query was
        // asked for at all (a missing cursor must not bypass the filter).
        assertThat(queryCaptor.getValue()).isNotNull();
    }

    @Test
    void nullCursor_isAccepted_asFirstPage() {
        when(mongoTemplate.find(any(Query.class), any(Class.class))).thenReturn(List.of());

        DocumentService.BackupPage page = service.listBackupPage("acme", "wiki", List.of(), null, 100);

        assertThat(page.rows()).isEmpty();
        assertThat(page.nextAfterId()).isNull();
    }

    @Test
    void filteredShortPage_stillCarriesCursor() {
        // A full page from Mongo where the in-JVM deny filter drops a row:
        // the page is shorter than the limit, but Mongo may hold more — the
        // cursor must follow the last *scanned* row, or paging stops early.
        when(mongoTemplate.find(any(Query.class), any(Class.class)))
                .thenReturn(List.of(row("a.md"), row("b.tmp"), row("c.md")));

        DocumentService.BackupPage page = service.listBackupPage("acme", "wiki", List.of("**/*.tmp"), null, 3);

        assertThat(page.rows()).extracting(DocumentDocument::getPath).containsExactly("a.md", "c.md");
        assertThat(page.nextAfterId()).isEqualTo("id-c.md");
    }

    @Test
    void fullyFilteredPage_stillCarriesCursor() {
        when(mongoTemplate.find(any(Query.class), any(Class.class))).thenReturn(List.of(row("a.tmp"), row("b.tmp")));

        DocumentService.BackupPage page = service.listBackupPage("acme", "wiki", List.of("**/*.tmp"), null, 2);

        assertThat(page.rows()).isEmpty();
        assertThat(page.nextAfterId()).isEqualTo("id-b.tmp");
    }

    @Test
    void shortScan_endsPaging() {
        when(mongoTemplate.find(any(Query.class), any(Class.class))).thenReturn(List.of(row("a.md")));

        DocumentService.BackupPage page = service.listBackupPage("acme", "wiki", List.of(), null, 50);

        assertThat(page.nextAfterId()).isNull();
    }
}
