package de.mhus.vance.brain.tools.kinds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit coverage for the trash-purge authorization decision
 * ({@link KindToolSupport#canPurgeTrash}) — permission-system variant B:
 * a project admin may purge any trash entry, everyone else only their own.
 * The admin verdict is asked of the pluggable {@link PermissionService}
 * ({@code ADMIN} on the project), never a hard-coded role test.
 */
@ExtendWith(MockitoExtension.class)
class KindToolSupportCanPurgeTrashTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "research";
    private static final String OWNER = "alice";

    @Mock PermissionService permissionService;
    @Mock SecurityContextFactory contextFactory;

    private KindToolSupport support;

    @BeforeEach
    void setUp() {
        support = new KindToolSupport(null, null, null, null,
                permissionService, contextFactory, null);
    }

    private ToolInvocationContext ctx(String userId) {
        return new ToolInvocationContext(TENANT, PROJECT, null, "proc-1", userId);
    }

    private DocumentDocument trashDoc(String createdBy) {
        return DocumentDocument.builder()
                .projectId(PROJECT)
                .createdBy(createdBy)
                .path("_bin/1234_secret.md")
                .build();
    }

    private void stubSubject(String userId, GrantAdmin admin) {
        SecurityContext subject = SecurityContext.user(userId, TENANT, List.of());
        when(contextFactory.forToolSubject(TENANT, userId)).thenReturn(subject);
        when(permissionService.check(
                eq(subject),
                any(Resource.Project.class),
                eq(Action.ADMIN)))
                .thenReturn(admin == GrantAdmin.YES);
    }

    private enum GrantAdmin { YES, NO }

    @Test
    void canPurgeTrash_projectAdmin_purgesAnyEntry() {
        stubSubject("boss", GrantAdmin.YES);

        boolean allowed = support.canPurgeTrash(ctx("boss"), trashDoc(OWNER));

        assertThat(allowed).isTrue();
    }

    @Test
    void canPurgeTrash_nonAdminOwner_purgesOwnEntry() {
        stubSubject(OWNER, GrantAdmin.NO);

        boolean allowed = support.canPurgeTrash(ctx(OWNER), trashDoc(OWNER));

        assertThat(allowed).isTrue();
    }

    @Test
    void canPurgeTrash_nonAdminNonOwner_denied() {
        stubSubject("mallory", GrantAdmin.NO);

        boolean allowed = support.canPurgeTrash(ctx("mallory"), trashDoc(OWNER));

        assertThat(allowed).isFalse();
    }

    @Test
    void canPurgeTrash_nonAdminUnknownCreator_denied() {
        stubSubject(OWNER, GrantAdmin.NO);

        boolean allowed = support.canPurgeTrash(ctx(OWNER), trashDoc(null));

        assertThat(allowed).isFalse();
    }
}
