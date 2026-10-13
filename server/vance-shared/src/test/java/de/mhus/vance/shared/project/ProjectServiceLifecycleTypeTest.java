package de.mhus.vance.shared.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.audit.AuditService;
import de.mhus.vance.shared.megadodo.MegadodoService;
import de.mhus.vance.shared.permission.PermissionService;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Only the tenant project is homeless. Every other project — the personal
 * hubs {@code _user_<login>} included — starts as {@link LifecycleType#AUTO}
 * and is placed through a lease.
 */
class ProjectServiceLifecycleTypeTest {

    private final ProjectRepository repository = mock(ProjectRepository.class);
    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<PermissionService> permissions = mock(ObjectProvider.class);

    private final ProjectService service = new ProjectService(
            repository, mongoTemplate, mock(AuditService.class), mock(MegadodoService.class), permissions);

    @Test
    void create_tenantProject_isHomeless() {
        when(repository.save(any(ProjectDocument.class))).thenAnswer(inv -> inv.getArgument(0));

        ProjectDocument created =
                service.create("acme", ProjectService.TENANT_PROJECT_NAME, null, null, List.of(), ProjectKind.SYSTEM);

        assertThat(created.getLifecycleType()).isEqualTo(LifecycleType.HOMELESS);
    }

    @Test
    void create_userHub_isAutoLikeAnyProject() {
        when(repository.save(any(ProjectDocument.class))).thenAnswer(inv -> inv.getArgument(0));

        ProjectDocument created = service.create("acme", "_user_marvin", null, null, List.of(), ProjectKind.SYSTEM);

        assertThat(created.getLifecycleType()).isEqualTo(LifecycleType.AUTO);
    }

    @Test
    void isHomeless_onlyTheTenantProject() {
        assertThat(ProjectService.isHomeless("_tenant")).isTrue();
        assertThat(ProjectService.isHomeless("_user_marvin")).isFalse();
        assertThat(ProjectService.isHomeless("kunde-x")).isFalse();
        assertThat(ProjectService.isHomeless(null)).isFalse();
    }

    @Test
    void claim_tenantProject_isRefused() {
        assertThatThrownBy(() ->
                        service.claim("acme", "_tenant", "pod-1", "node-1", "10.0.0.1:9990", Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void setLifecycleType_userHub_isRefused() {
        ProjectDocument hub = ProjectDocument.builder()
                .tenantId("acme")
                .name("_user_marvin")
                .kind(ProjectKind.SYSTEM)
                .lifecycleType(LifecycleType.AUTO)
                .build();
        when(repository.findByTenantIdAndName("acme", "_user_marvin")).thenReturn(Optional.of(hub));

        assertThatThrownBy(() -> service.setLifecycleType("acme", "_user_marvin", LifecycleType.PERMANENT))
                .isInstanceOf(ProjectService.SystemProjectProtectedException.class);
    }
}
