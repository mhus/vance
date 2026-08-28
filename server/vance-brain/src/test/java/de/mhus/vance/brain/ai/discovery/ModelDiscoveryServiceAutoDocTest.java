package de.mhus.vance.brain.ai.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.brain.ai.AiModelProvider;
import de.mhus.vance.brain.ai.AiModelService;
import de.mhus.vance.brain.ai.DiscoveredModelInfo;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.ai.ProviderType;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.WriteActor;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.settings.SettingDocument;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.tenant.TenantDocument;
import de.mhus.vance.shared.tenant.TenantService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Pins the content contract of the auto-discovered model docs.
 *
 * <p>Regression: the Gemini provider reported {@code kind: "chat"} for
 * every entry its listing returned, including
 * {@code gemini-2.5-flash-image}. Because the auto layer outranks the
 * bundled layer in the {@link ModelCatalog} cascade, that wrote over the
 * bundled {@code kind: image}, the model disappeared from
 * {@code listAllImages}, and the LLM setting-form then rejected the
 * still-configured {@code ai.alias.default.image} with
 * {@code invalid_choice}.
 *
 * <p>The fix is structural: {@link DiscoveredModelInfo} carries no
 * {@code kind} at all, so no provider can assert one.
 */
class ModelDiscoveryServiceAutoDocTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "_tenant";

    private final TenantService tenantService = mock(TenantService.class);
    private final ProjectService projectService = mock(ProjectService.class);
    private final SettingService settingService = mock(SettingService.class);
    private final AiModelService aiModelService = mock(AiModelService.class);
    private final DocumentService documentService = mock(DocumentService.class);
    private final ModelCatalog modelCatalog = mock(ModelCatalog.class);

    private final ModelDiscoveryService service = new ModelDiscoveryService(
            tenantService, projectService, settingService,
            aiModelService, documentService, modelCatalog);

    @Test
    void auto_doc_never_claims_a_model_kind() {
        String yaml = runDiscoveryFor(DiscoveredModelInfo.of("gemini-2.5-flash-image"));

        assertThat(yaml).doesNotContain("kind:");
    }

    @Test
    void auto_doc_carries_the_observed_fields() {
        String yaml = runDiscoveryFor(
                DiscoveredModelInfo.withWindow("gemini-2.0-flash", 1048576));

        assertThat(yaml)
                .contains("contextWindowTokens: 1048576")
                .contains("discoveredBy: discovery-job")
                .contains("discoveredAt:");
    }

    /**
     * An instance whose protocol is declared only by its
     * {@code _provider.yaml} must be discoverable, not skipped.
     *
     * <p>Discovery derives the protocol from the instance name when no
     * {@code .type} setting exists, so {@code cortecs} used to resolve to
     * no {@link ProviderType} and drop out with a debug line. The resolver
     * reads the sidecar and would happily chat with the same instance —
     * meaning "Discover AI Models" silently did nothing for exactly the
     * gateway the operator had just configured.
     */
    @Test
    void instanceWithProtocolOnlyInTheSidecar_isDiscovered() {
        when(tenantService.findByName(TENANT))
                .thenReturn(Optional.of(new TenantDocument()));
        ProjectDocument project = new ProjectDocument();
        project.setName(PROJECT);
        when(projectService.all(TENANT)).thenReturn(List.of(project));

        SettingDocument apiKey = new SettingDocument();
        apiKey.setKey("ai.provider.cortecs.apiKey");
        apiKey.setType(SettingType.PASSWORD);
        when(settingService.findAll(TENANT, SettingService.SCOPE_PROJECT, PROJECT))
                .thenReturn(List.of(apiKey));
        when(settingService.getDecryptedPassword(
                TENANT, SettingService.SCOPE_PROJECT, PROJECT, "ai.provider.cortecs.apiKey"))
                .thenReturn("test-key");
        when(modelCatalog.lookupProvider(TENANT, PROJECT, "cortecs"))
                .thenReturn(Optional.of(java.util.Map.of("wireType", "openai")));

        AiModelProvider provider = mock(AiModelProvider.class);
        when(provider.listAvailableModels(any()))
                .thenReturn(List.of(DiscoveredModelInfo.of("llama-3.3-70b")));
        when(aiModelService.findProvider(ProviderType.OPENAI)).thenReturn(Optional.of(provider));

        ModelDiscoveryService.DiscoveryResult result = service.discoverForTenant(TENANT);

        assertThat(result.modelsWritten()).isEqualTo(1);
        verify(documentService).upsertText(
                eq(TENANT), eq(PROJECT),
                // written under the *instance* name, not the wire type —
                // the auto layer has to line up with the manual one.
                eq(ModelDiscoveryService.AUTO_PATH_PREFIX + "cortecs/llama-3.3-70b.yaml"),
                any(), any(), any(), any(), eq(WriteActor.SYSTEM));
    }

    /**
     * Drives a one-project, one-instance, one-model discovery pass and
     * returns the YAML body handed to {@link DocumentService#upsertText}.
     */
    private String runDiscoveryFor(DiscoveredModelInfo model) {
        when(tenantService.findByName(TENANT))
                .thenReturn(Optional.of(new TenantDocument()));
        ProjectDocument project = new ProjectDocument();
        project.setName(PROJECT);
        when(projectService.all(TENANT)).thenReturn(List.of(project));

        SettingDocument apiKey = new SettingDocument();
        apiKey.setKey("ai.provider.gemini.apiKey");
        apiKey.setType(SettingType.PASSWORD);
        when(settingService.findAll(TENANT, SettingService.SCOPE_PROJECT, PROJECT))
                .thenReturn(List.of(apiKey));
        when(settingService.getDecryptedPassword(
                TENANT, SettingService.SCOPE_PROJECT, PROJECT, "ai.provider.gemini.apiKey"))
                .thenReturn("test-key");

        AiModelProvider provider = mock(AiModelProvider.class);
        when(provider.listAvailableModels(any())).thenReturn(List.of(model));
        when(aiModelService.findProvider(ProviderType.GEMINI)).thenReturn(Optional.of(provider));

        ModelDiscoveryService.DiscoveryResult result = service.discoverForTenant(TENANT);
        assertThat(result.modelsWritten()).isEqualTo(1);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(documentService).upsertText(
                eq(TENANT), eq(PROJECT), any(), any(), any(),
                body.capture(), any(), eq(WriteActor.SYSTEM));
        return body.getValue();
    }
}
