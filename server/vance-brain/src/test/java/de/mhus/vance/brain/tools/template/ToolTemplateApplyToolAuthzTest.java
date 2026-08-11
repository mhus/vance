package de.mhus.vance.brain.tools.template;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.kit.ToolTemplateCatalogEntry;
import de.mhus.vance.brain.kit.KitService;
import de.mhus.vance.brain.kit.TemplateApplier;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.kit.catalog.ToolTemplateCatalogService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionDeniedException;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Authorization gate on {@code tool_template_apply}. {@code projectId} is a
 * tool parameter, so the dispatcher's caller-scope check never sees the write
 * target — without the explicit enforce an agent could install tool
 * credentials (documents + encrypted settings) into any project of the tenant.
 */
class ToolTemplateApplyToolAuthzTest {

    private static final ToolInvocationContext CTX =
            new ToolInvocationContext("acme", "home", "sess", "proc", "alice");
    private static final SecurityContext SUBJECT =
            SecurityContext.user("alice", "acme", List.of());

    private final ToolTemplateCatalogService catalogService = mock(ToolTemplateCatalogService.class);
    private final KitService kitService = mock(KitService.class);
    private final PermissionService permissionService = mock(PermissionService.class);
    private final SecurityContextFactory contextFactory = mock(SecurityContextFactory.class);

    private final ToolTemplateApplyTool tool = new ToolTemplateApplyTool(
            catalogService, kitService, permissionService, contextFactory);

    /** Target project deliberately differs from the caller's scope ("home"). */
    private static Map<String, Object> params() {
        return Map.of("name", "jira", "projectId", "other-project",
                "inputs", Map.of("url", "https://example.tld"));
    }

    @Test
    void apply_enforces_admin_on_the_target_project_not_on_the_caller_scope() {
        when(contextFactory.forToolSubject("acme", "alice")).thenReturn(SUBJECT);
        when(catalogService.findByName("acme", "jira"))
                .thenReturn(mock(ToolTemplateCatalogEntry.class));
        when(kitService.applyTemplate(any(), any(), any(), any(), any(), any()))
                .thenReturn(new TemplateApplier.ApplyResult(null, null, "jira"));

        tool.invoke(params(), CTX);

        verify(contextFactory).forToolSubject("acme", "alice");
        verify(permissionService).enforce(
                eq(SUBJECT),
                eq(new Resource.Project("acme", "other-project")),
                eq(Action.ADMIN));
    }

    @Test
    void apply_touches_neither_catalog_nor_applier_when_authorization_fails() {
        when(contextFactory.forToolSubject("acme", "alice")).thenReturn(SUBJECT);
        doThrow(new PermissionDeniedException(
                SUBJECT, new Resource.Project("acme", "other-project"), Action.ADMIN))
                .when(permissionService).enforce(any(), any(), any());

        assertThatThrownBy(() -> tool.invoke(params(), CTX))
                .isInstanceOf(PermissionDeniedException.class);

        verifyNoInteractions(catalogService);
        verify(kitService, never()).applyTemplate(any(), any(), any(), any(), any(), any());
    }

    @Test
    void apply_requires_a_tenant_scope() {
        ToolInvocationContext noTenant =
                new ToolInvocationContext(null, "home", "sess", "proc", "alice");

        assertThatThrownBy(() -> tool.invoke(params(), noTenant))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("tenant scope");

        verifyNoInteractions(permissionService);
    }
}
