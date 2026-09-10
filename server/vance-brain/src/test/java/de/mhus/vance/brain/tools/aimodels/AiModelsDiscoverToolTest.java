package de.mhus.vance.brain.tools.aimodels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.ai.discovery.ModelDiscoveryService;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionDeniedException;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The discovery tool is the LLM twin of the admin REST endpoint, so the
 * one thing that must not drift is the gate: ADMIN on the tenant, taken
 * from the tool subject (real user checked, headless passes as SYSTEM).
 * Everything else is a plain pass-through of the service result.
 */
class AiModelsDiscoverToolTest {

    private static final String TENANT = "acme";
    private static final String USER = "road.runner";

    private ModelDiscoveryService discoveryService;
    private PermissionService permissionService;
    private SecurityContextFactory contextFactory;
    private AiModelsDiscoverTool tool;
    private ToolInvocationContext ctx;

    @BeforeEach
    void setUp() {
        discoveryService = mock(ModelDiscoveryService.class);
        permissionService = mock(PermissionService.class);
        contextFactory = mock(SecurityContextFactory.class);
        when(contextFactory.forToolSubject(TENANT, USER)).thenReturn(SecurityContext.user(USER, TENANT, List.of()));
        tool = new AiModelsDiscoverTool(discoveryService, permissionService, contextFactory);
        ctx = new ToolInvocationContext(TENANT, "model-sipgate-coding", "sess1", "proc1", USER);
    }

    @Test
    void metadata_isDeferredWithAimodelsLabel() {
        assertThat(tool.name()).isEqualTo("ai_models_discover");
        assertThat(tool.primary()).isFalse();
        assertThat(tool.labels()).contains("aimodels");
    }

    @Test
    void invoke_enforcesAdminOnTenantBeforeDiscovery() {
        when(discoveryService.discoverForTenant(TENANT)).thenReturn(result(2, 3, 7));

        Map<String, Object> out = tool.invoke(Map.of(), ctx);

        verify(permissionService)
                .enforce(SecurityContext.user(USER, TENANT, List.of()), new Resource.Tenant(TENANT), Action.ADMIN);
        verify(discoveryService).discoverForTenant(TENANT);
        assertThat(out.get("tenantId")).isEqualTo(TENANT);
        assertThat(out.get("scopesScanned")).isEqualTo(2);
        assertThat(out.get("instancesScanned")).isEqualTo(3);
        assertThat(out.get("modelsWritten")).isEqualTo(7);
        assertThat(out.get("modelsFailed")).isEqualTo(0);
        assertThat((Map<?, ?>) out.get("skippedInstances")).isEmpty();
    }

    @Test
    void invoke_deniedNonAdminPropagatesAndNeverCallsDiscovery() {
        org.mockito.Mockito.doThrow(new PermissionDeniedException(
                        SecurityContext.user(USER, TENANT, List.of()), new Resource.Tenant(TENANT), Action.ADMIN))
                .when(permissionService)
                .enforce(any(), any(), any());

        assertThatThrownBy(() -> tool.invoke(Map.of(), ctx)).isInstanceOf(PermissionDeniedException.class);

        verify(discoveryService, never()).discoverForTenant(TENANT);
    }

    @Test
    void invoke_serviceFailureWrapsAsToolException() {
        when(discoveryService.discoverForTenant(TENANT)).thenThrow(new IllegalStateException("listing endpoint down"));

        assertThatThrownBy(() -> tool.invoke(Map.of(), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("listing endpoint down");
    }

    private static ModelDiscoveryService.DiscoveryResult result(int scopes, int instances, int written) {
        return new ModelDiscoveryService.DiscoveryResult(
                TENANT, scopes, instances, written, 0, 0, 0, Map.of(), 12_345L, Instant.now());
    }
}
