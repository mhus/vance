package de.mhus.vance.shared.toolhealth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.toolhealth.ToolHealthClassification;
import de.mhus.vance.api.toolhealth.ToolHealthScope;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Guards the optimistic-lock retry on ToolHealth cooldown/status writes
 * (code-review Phase 2): a version conflict from a concurrent writer must
 * re-read and re-apply, not lose the update or bubble the exception.
 */
class ToolHealthServiceRetryTest {

    private ToolHealthRepository repository;
    private ToolHealthService service;

    @BeforeEach
    void setUp() {
        repository = mock(ToolHealthRepository.class);
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        service = new ToolHealthService(mongoTemplate, repository);
        // Each read returns a FRESH document — models Mongo re-reads on retry
        // (the failed attempt's in-memory mutations are not persisted).
        when(repository.findByTenantIdAndScopeAndScopeIdAndToolName(
                any(), any(), any(), any()))
                .thenAnswer(inv -> Optional.of(ToolHealthDocument.builder()
                        .tenantId("acme").scope(ToolHealthScope.PROJECT)
                        .scopeId("proj").toolName("github.merge_pr").build()));
    }

    @Test
    void setCooldown_retriesOnVersionConflict_thenSucceeds() {
        // First save loses the optimistic-lock race; the second wins.
        when(repository.save(any()))
                .thenThrow(new OptimisticLockingFailureException("version conflict"))
                .thenAnswer(inv -> inv.getArgument(0));

        var cooldown = service.setCooldown(
                "acme", ToolHealthScope.PROJECT, "proj", "github.merge_pr",
                "sig-1", "alice", ToolHealthClassification.UNCLEAR,
                Duration.ofMinutes(5), "note");

        assertThat(cooldown).isNotNull();
        assertThat(cooldown.getErrorSignature()).isEqualTo("sig-1");
        verify(repository, times(2)).save(any());
    }

    @Test
    void setCooldown_propagatesWhenRetryBudgetExhausted() {
        when(repository.save(any()))
                .thenThrow(new OptimisticLockingFailureException("always conflicts"));

        assertThatThrownBy(() -> service.setCooldown(
                "acme", ToolHealthScope.PROJECT, "proj", "github.merge_pr",
                "sig-1", "alice", ToolHealthClassification.UNCLEAR,
                Duration.ofMinutes(5), "note"))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }
}
