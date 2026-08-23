package de.mhus.vance.shared.toolhealth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.toolhealth.ToolHealthClassification;
import de.mhus.vance.api.toolhealth.ToolHealthScope;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Guards the repair of rows written before the {@code @Version} field
 * existed. Spring Data reads a null version as "new entity" and turns the
 * next save into an insert, which collides with the row's own {@code _id}
 * — and unlike a real version conflict the retry cannot resolve it,
 * because every re-read yields the same version-less row.
 */
class ToolHealthServiceLegacyVersionTest {

    private ToolHealthRepository repository;
    private MongoTemplate mongoTemplate;
    private ToolHealthService service;

    @BeforeEach
    void setUp() {
        repository = mock(ToolHealthRepository.class);
        mongoTemplate = mock(MongoTemplate.class);
        service = new ToolHealthService(mongoTemplate, repository, mock(de.mhus.vance.shared.megadodo.MegadodoService.class));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private void givenStoredDocument(String id, Long version) {
        when(repository.findByTenantIdAndScopeAndScopeIdAndToolName(any(), any(), any(), any()))
                .thenAnswer(inv -> Optional.of(ToolHealthDocument.builder()
                        .id(id).version(version)
                        .tenantId("acme").scope(ToolHealthScope.PROJECT)
                        .scopeId("proj").toolName("doc_edit").build()));
    }

    @Test
    void setCooldown_legacyRowWithoutVersion_backfillsVersionBeforeSaving() {
        givenStoredDocument("6a3aa1c4e1ec6b03c8376838", null);

        service.setCooldown(
                "acme", ToolHealthScope.PROJECT, "proj", "doc_edit",
                "sig-1", "alice", ToolHealthClassification.UNCLEAR,
                Duration.ofMinutes(5), "note");

        verify(mongoTemplate).updateFirst(
                any(Query.class), any(Update.class), eq(ToolHealthDocument.class));

        ArgumentCaptor<ToolHealthDocument> saved = ArgumentCaptor.forClass(ToolHealthDocument.class);
        verify(repository).save(saved.capture());
        // Without a non-null version Spring Data would route save() to insert.
        assertThat(saved.getValue().getVersion()).isEqualTo(0L);
    }

    @Test
    void setCooldown_alreadyVersionedRow_isNotTouchedByTheRepair() {
        givenStoredDocument("6a3aa1c4e1ec6b03c8376838", 7L);

        service.setCooldown(
                "acme", ToolHealthScope.PROJECT, "proj", "doc_edit",
                "sig-1", "alice", ToolHealthClassification.UNCLEAR,
                Duration.ofMinutes(5), "note");

        verify(mongoTemplate, never()).updateFirst(
                any(Query.class), any(Update.class), eq(ToolHealthDocument.class));
        ArgumentCaptor<ToolHealthDocument> saved = ArgumentCaptor.forClass(ToolHealthDocument.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getVersion()).isEqualTo(7L);
    }

    @Test
    void noteSuccessfulCall_legacyRowWithoutVersion_canFlipStatusBackToOk() {
        // The regression that kept 'doc_edit' DEGRADED for six weeks: the
        // auto-clear on a successful invocation went through the same
        // insert-instead-of-update path and never took effect.
        when(repository.findByTenantIdAndScopeAndScopeIdAndToolName(any(), any(), any(), any()))
                .thenAnswer(inv -> ToolHealthScope.PROJECT.equals(inv.getArgument(1))
                        ? Optional.of(ToolHealthDocument.builder()
                                .id("6a3aa1c4e1ec6b03c8376838").version(null)
                                .tenantId("acme").scope(ToolHealthScope.PROJECT)
                                .scopeId("proj").toolName("doc_edit")
                                .status(de.mhus.vance.api.toolhealth.ToolHealthStatus.DEGRADED)
                                .build())
                        : Optional.empty());

        service.noteSuccessfulCall("acme", null, "alice", "proj", "doc_edit");

        ArgumentCaptor<ToolHealthDocument> saved = ArgumentCaptor.forClass(ToolHealthDocument.class);
        verify(repository, times(1)).save(saved.capture());
        assertThat(saved.getValue().getStatus())
                .isEqualTo(de.mhus.vance.api.toolhealth.ToolHealthStatus.OK);
        assertThat(saved.getValue().getVersion()).isEqualTo(0L);
    }
}
