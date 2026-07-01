package de.mhus.vance.shared.sessiongroup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Behaviour tests for {@link SessionGroupService}. Mongo is stubbed — we
 * verify sort-index assignment, the at-most-one-group invariant on assign,
 * and the not-found / duplicate failure paths.
 */
class SessionGroupServiceTest {

    private static final String T = "acme";
    private static final String P = "proj";
    private static final String U = "alice";

    private SessionGroupRepository repository;
    private MongoTemplate mongoTemplate;
    private SessionGroupService service;

    @BeforeEach
    void setUp() {
        repository = mock(SessionGroupRepository.class);
        mongoTemplate = mock(MongoTemplate.class);
        service = new SessionGroupService(repository, mongoTemplate);
    }

    private SessionGroupDocument group(String name, int sortIndex) {
        return SessionGroupDocument.builder()
                .tenantId(T).projectId(P).userId(U).name(name).sortIndex(sortIndex).build();
    }

    @Test
    void create_firstGroup_getsSortIndexZero() {
        when(repository.existsByTenantIdAndProjectIdAndUserIdAndName(T, P, U, "research"))
                .thenReturn(false);
        when(repository.findByTenantIdAndProjectIdAndUserIdOrderBySortIndexAsc(T, P, U))
                .thenReturn(List.of());
        when(repository.save(any(SessionGroupDocument.class))).thenAnswer(inv -> inv.getArgument(0));

        SessionGroupDocument saved = service.create(T, P, U, "research", "Research");

        assertThat(saved.getSortIndex()).isZero();
        assertThat(saved.getTitle()).isEqualTo("Research");
    }

    @Test
    void create_appendsAfterHighestSortIndex() {
        when(repository.existsByTenantIdAndProjectIdAndUserIdAndName(T, P, U, "third"))
                .thenReturn(false);
        when(repository.findByTenantIdAndProjectIdAndUserIdOrderBySortIndexAsc(T, P, U))
                .thenReturn(List.of(group("a", 0), group("b", 4)));
        when(repository.save(any(SessionGroupDocument.class))).thenAnswer(inv -> inv.getArgument(0));

        SessionGroupDocument saved = service.create(T, P, U, "third", null);

        assertThat(saved.getSortIndex()).isEqualTo(5);
    }

    @Test
    void create_duplicateName_throws() {
        when(repository.existsByTenantIdAndProjectIdAndUserIdAndName(T, P, U, "dup"))
                .thenReturn(true);

        assertThatThrownBy(() -> service.create(T, P, U, "dup", null))
                .isInstanceOf(SessionGroupService.SessionGroupAlreadyExistsException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void rename_missingGroup_throws() {
        when(repository.findByTenantIdAndProjectIdAndUserIdAndName(T, P, U, "ghost"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rename(T, P, U, "ghost", "X"))
                .isInstanceOf(SessionGroupService.SessionGroupNotFoundException.class);
    }

    @Test
    void assign_movesSessionOutOfOtherGroupsThenIntoTarget() {
        when(repository.findByTenantIdAndProjectIdAndUserIdAndName(T, P, U, "target"))
                .thenReturn(Optional.of(group("target", 0)));

        service.assign(T, P, U, "sess_1", "target");

        // Pull from all groups in scope first.
        ArgumentCaptor<Update> pull = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateMulti(any(Query.class), pull.capture(), eq(SessionGroupDocument.class));
        assertThat(pull.getValue().getUpdateObject().toJson()).contains("$pull").contains("sess_1");

        // Then add to the target group.
        ArgumentCaptor<Update> add = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(any(Query.class), add.capture(), eq(SessionGroupDocument.class));
        assertThat(add.getValue().getUpdateObject().toJson()).contains("$addToSet").contains("sess_1");
    }

    @Test
    void assign_nullGroup_onlyPullsFromAll() {
        service.assign(T, P, U, "sess_1", null);

        verify(mongoTemplate).updateMulti(any(Query.class), any(Update.class), eq(SessionGroupDocument.class));
        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class), eq(SessionGroupDocument.class));
    }

    @Test
    void assign_missingTargetGroup_throws() {
        when(repository.findByTenantIdAndProjectIdAndUserIdAndName(T, P, U, "ghost"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assign(T, P, U, "sess_1", "ghost"))
                .isInstanceOf(SessionGroupService.SessionGroupNotFoundException.class);
        verify(mongoTemplate, never()).updateMulti(any(Query.class), any(Update.class), eq(SessionGroupDocument.class));
    }

    @Test
    void reorder_assignsSortIndexFromPositionAndIgnoresUnknown() {
        SessionGroupDocument a = group("a", 5);
        SessionGroupDocument b = group("b", 9);
        when(repository.findByTenantIdAndProjectIdAndUserIdOrderBySortIndexAsc(T, P, U))
                .thenReturn(List.of(a, b));
        when(repository.save(any(SessionGroupDocument.class))).thenAnswer(inv -> inv.getArgument(0));

        service.reorder(T, P, U, List.of("b", "unknown", "a"));

        assertThat(b.getSortIndex()).isZero();  // position 0
        assertThat(a.getSortIndex()).isEqualTo(1);  // position 2, but "unknown" skipped so index 1
    }
}
