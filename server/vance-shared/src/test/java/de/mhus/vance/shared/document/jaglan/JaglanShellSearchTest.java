package de.mhus.vance.shared.document.jaglan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.documents.MountAccess;
import de.mhus.vance.api.mount.MountedSource;
import de.mhus.vance.api.mount.MountedStat;
import de.mhus.vance.shared.document.DocumentDocument;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Delegated search: the mounts answer, we upsert what they said, and one
 * failing mount does not take the others down.
 */
class JaglanShellSearchTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "research";

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
    }

    private static MountedSource source(String name) {
        return new MountedSource(
                name, null, "ode", MountAccess.RO, null, null, Duration.ofMinutes(5), true);
    }

    /** What the dispatcher now returns: hits plus the outcome. */
    private static JaglanPort.MountSearchResult delegated(MountedStat... hits) {
        return new JaglanPort.MountSearchResult(
                List.of(hits), de.mhus.vance.api.documents.MountSearchOutcome.DELEGATED);
    }

    private static MountedStat hit(String path) {
        return new MountedStat(path, false, 12, "application/pdf", "e1", null,
                MountAccess.RO, "Dune");
    }

    private void mounts(String... names) {
        List<MountedSource> sources = new java.util.ArrayList<>();
        for (String name : names) sources.add(source(name));
        when(port.mounts(TENANT, PROJECT)).thenReturn(sources);
    }

    /** The row the upsert-then-read cycle hands back. */
    private void mongoReadsBack() {
        DocumentDocument row = DocumentDocument.builder()
                .tenantId(TENANT).projectId(PROJECT)
                .path("_ext/library/books/dune.pdf").name("dune.pdf")
                .title("Dune").mimeType("application/pdf").size(12)
                .build();
        row.setId("ext_x");
        when(mongoTemplate.findById(anyString(), eq(DocumentDocument.class))).thenReturn(row);
    }

    @Test
    void search_upsertsWhatTheMountReturned() {
        mounts("library");
        mongoReadsBack();
        when(port.search(TENANT, PROJECT, "library", "dune", 20))
                .thenReturn(delegated(hit("books/dune.pdf")));

        List<DocumentDocument> results = service.search(TENANT, PROJECT, null, "dune", 20);

        // Written, not only returned: otherwise the first doc_read on a hit
        // pays another stat for a file we just described.
        assertThat(results).hasSize(1);
        verify(mongoTemplate).upsert(any(Query.class), any(Update.class),
                eq(DocumentDocument.class));
    }

    @Test
    void search_carriesTheSourceTitleIntoTheShellRow() {
        mounts("library");
        mongoReadsBack();
        when(port.search(anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(delegated(hit("books/dune.pdf")));

        service.search(TENANT, PROJECT, null, "dune", 20);

        ArgumentCaptor<Update> captor = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).upsert(any(Query.class), captor.capture(),
                eq(DocumentDocument.class));
        // A library that knows a book's title should not have to press it into
        // the file name.
        assertThat(captor.getValue().getUpdateObject()
                .get("$set", org.bson.Document.class).get("title")).isEqualTo("Dune");
    }

    @Test
    void search_asksEveryMountWhenNoneIsNamed() {
        mounts("library", "archive");
        mongoReadsBack();
        when(port.search(anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(delegated());

        service.search(TENANT, PROJECT, null, "dune", 20);

        verify(port).search(TENANT, PROJECT, "library", "dune", 20);
        verify(port).search(TENANT, PROJECT, "archive", "dune", 20);
    }

    @Test
    void search_restrictedToOneMount_skipsTheOthers() {
        mounts("library", "archive");
        mongoReadsBack();
        when(port.search(anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(delegated());

        service.search(TENANT, PROJECT, "archive", "dune", 20);

        verify(port, never()).search(anyString(), anyString(), eq("library"), anyString(), anyInt());
        verify(port).search(TENANT, PROJECT, "archive", "dune", 20);
    }

    @Test
    void search_oneFailingMount_doesNotFailTheOthers() {
        mounts("broken", "library");
        mongoReadsBack();
        when(port.search(anyString(), anyString(), eq("broken"), anyString(), anyInt()))
                .thenThrow(new JaglanUnavailableException("broken", "connect timeout"));
        when(port.search(anyString(), anyString(), eq("library"), anyString(), anyInt()))
                .thenReturn(delegated(hit("books/dune.pdf")));

        List<DocumentDocument> results = service.search(TENANT, PROJECT, null, "dune", 20);

        assertThat(results).hasSize(1);
    }

    @Test
    void search_aFailedMountIsMutedForTheNextCall() {
        mounts("broken");
        when(port.search(anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenThrow(new JaglanUnavailableException("broken", "connect timeout"));

        service.search(TENANT, PROJECT, null, "dune", 20);
        service.search(TENANT, PROJECT, null, "dune", 20);

        verify(port, times(1)).search(anyString(), anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void search_respectsTheLimitAcrossMounts() {
        mounts("a", "b");
        mongoReadsBack();
        when(port.search(anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(delegated(hit("1.pdf"), hit("2.pdf")));

        assertThat(service.search(TENANT, PROJECT, null, "dune", 3)).hasSize(3);
    }

    @Test
    void search_blankQuery_asksNobody() {
        mounts("library");

        assertThat(service.search(TENANT, PROJECT, null, "  ", 20)).isEmpty();
        verify(port, never()).search(anyString(), anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void search_withoutAPort_isEmpty() {
        @SuppressWarnings("unchecked")
        ObjectProvider<JaglanPort> empty = mock(ObjectProvider.class);
        when(empty.getIfAvailable()).thenReturn(null);

        assertThat(new JaglanShellService(mongoTemplate, empty)
                .search(TENANT, PROJECT, null, "dune", 20)).isEmpty();
    }
}
