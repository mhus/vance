package de.mhus.vance.shared.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.audit.AuditService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * The project-visibility READ check is a hard check owned by the source
 * (ProjectService), not by each frontend (permission-system finding #11).
 */
class ProjectServiceReadableTest {

    private final ProjectRepository repository = mock(ProjectRepository.class);
    private final PermissionService permissionService = mock(PermissionService.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<PermissionService> provider = mock(ObjectProvider.class);

    private final ProjectService service = new ProjectService(
            repository, mock(MongoTemplate.class), mock(AuditService.class), provider);

    @Test
    void listReadableBy_keeps_only_readable_projects() {
        when(provider.getObject()).thenReturn(permissionService);
        SecurityContext alice = SecurityContext.user("alice", "acme", List.of());
        when(repository.findByTenantId("acme"))
                .thenReturn(List.of(project("mine"), project("secret")));
        when(permissionService.check(eq(alice),
                eq(new Resource.Project("acme", "mine")), eq(Action.READ))).thenReturn(true);
        when(permissionService.check(eq(alice),
                eq(new Resource.Project("acme", "secret")), eq(Action.READ))).thenReturn(false);

        List<ProjectDocument> visible = service.listReadableBy("acme", alice);

        assertThat(visible).extracting(ProjectDocument::getName).containsExactly("mine");
    }

    private static ProjectDocument project(String name) {
        ProjectDocument p = new ProjectDocument();
        p.setName(name);
        return p;
    }
}
