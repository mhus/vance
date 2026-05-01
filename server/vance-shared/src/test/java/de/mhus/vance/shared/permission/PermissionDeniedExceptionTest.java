package de.mhus.vance.shared.permission;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PermissionDeniedExceptionTest {

    @Test
    void exposesSubjectResourceAndAction() {
        SecurityContext subject = SecurityContext.user("alice", "acme", List.of());
        Resource resource = new Resource.Document("acme", "proj", "_vance/foo.md");

        PermissionDeniedException ex =
                new PermissionDeniedException(subject, resource, Action.WRITE);

        assertThat(ex.getSubject()).isSameAs(subject);
        assertThat(ex.getResource()).isSameAs(resource);
        assertThat(ex.getAction()).isEqualTo(Action.WRITE);
    }

    @Test
    void messageContainsSubjectTenantActionAndResource() {
        SecurityContext subject = SecurityContext.user("alice", "acme", List.of());
        Resource resource = new Resource.Project("acme", "proj");

        PermissionDeniedException ex =
                new PermissionDeniedException(subject, resource, Action.ADMIN);

        // The exact wording is implementation detail, but the diagnostic
        // bits a human grepping logs needs must be there.
        assertThat(ex.getMessage())
                .contains("USER", "alice", "acme", "ADMIN", "Project");
    }
}
