package de.mhus.vance.brain.ai.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.brain.ai.AiModelProvider;
import de.mhus.vance.brain.ai.AiModelService;
import de.mhus.vance.brain.ai.DiscoveredModelInfo;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.ai.ModelInfo;
import de.mhus.vance.brain.ai.ProviderType;
import de.mhus.vance.shared.document.DocumentDocument;
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
 * Pins the {@code auto: true} contract of the machine-owned manual
 * pricing docs: created when absent, refreshed while the marker stays,
 * never touched once an operator claims the file (marker removed).
 * Prices the endpoint does not report produce nothing — no file, no
 * guess, no scrape.
 */
class ModelDiscoveryServicePricingDocTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "_tenant";
    private static final String PRICING_PATH = "_vance/model/gemini/gemini-3.8-flash.yaml";

    private final TenantService tenantService = mock(TenantService.class);
    private final ProjectService projectService = mock(ProjectService.class);
    private final SettingService settingService = mock(SettingService.class);
    private final AiModelService aiModelService = mock(AiModelService.class);
    private final DocumentService documentService = mock(DocumentService.class);
    private final ModelCatalog modelCatalog = mock(ModelCatalog.class);

    private final ModelDiscoveryService service = new ModelDiscoveryService(
            tenantService, projectService, settingService, aiModelService, documentService, modelCatalog);

    @Test
    void created_with_auto_marker_when_no_file_exists() {
        when(documentService.findByPath(TENANT, PROJECT, PRICING_PATH)).thenReturn(Optional.empty());
        runDiscoveryFor(pricedModel());

        assertThat(result().pricingDocsCreated()).isEqualTo(1);
        assertThat(result().pricingDocsUpdated()).isEqualTo(0);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(documentService)
                .upsertText(
                        eq(TENANT),
                        eq(PROJECT),
                        eq(PRICING_PATH),
                        any(),
                        any(),
                        body.capture(),
                        any(),
                        eq(WriteActor.SYSTEM));
        assertThat(body.getValue())
                .contains("auto: true")
                .contains("pricing:")
                .contains("  currency: EUR")
                .contains("  inputPerMTok: 0.741")
                .contains("  outputPerMTok: 3.703")
                .contains("  cacheReadPerMTok: 0.074")
                .contains("  cacheWritePerMTok: 0.075")
                .contains("Remove the marker to take ownership");
    }

    @Test
    void untouched_when_file_exists_without_marker() {
        DocumentDocument operatorFile = mock(DocumentDocument.class);
        when(documentService.findByPath(TENANT, PROJECT, PRICING_PATH)).thenReturn(Optional.of(operatorFile));
        when(documentService.readContent(operatorFile))
                .thenReturn("pricing:\n  currency: USD\n  inputPerMTok: 1.0\n  outputPerMTok: 2.0\n");

        runDiscoveryFor(pricedModel());

        verify(documentService, never())
                .upsertText(eq(TENANT), eq(PROJECT), eq(PRICING_PATH), any(), any(), any(), any(), any());
        assertThat(result().pricingDocsCreated()).isEqualTo(0);
        assertThat(result().pricingDocsUpdated()).isEqualTo(0);
    }

    @Test
    void refreshed_when_file_exists_with_marker() {
        DocumentDocument machineFile = mock(DocumentDocument.class);
        when(documentService.findByPath(TENANT, PROJECT, PRICING_PATH)).thenReturn(Optional.of(machineFile));
        when(documentService.readContent(machineFile))
                .thenReturn("auto: true\npricing:\n  currency: EUR\n  inputPerMTok: 0.5\n  outputPerMTok: 2.0\n");

        runDiscoveryFor(pricedModel());

        assertThat(result().pricingDocsUpdated()).isEqualTo(1);
        assertThat(result().pricingDocsCreated()).isEqualTo(0);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(documentService)
                .upsertText(
                        eq(TENANT),
                        eq(PROJECT),
                        eq(PRICING_PATH),
                        any(),
                        any(),
                        body.capture(),
                        any(),
                        eq(WriteActor.SYSTEM));
        // Refreshed to the endpoint's current price, not the stale one.
        assertThat(body.getValue()).contains("  inputPerMTok: 0.741");
    }

    @Test
    void no_pricing_means_no_file_at_all() {
        runDiscoveryFor(DiscoveredModelInfo.of("gemini-2.0-flash"));

        verify(documentService, never()).findByPath(any(), any(), any());
        assertThat(result().pricingDocsCreated()).isEqualTo(0);
        assertThat(result().pricingDocsUpdated()).isEqualTo(0);
    }

    @Test
    void has_auto_marker_variants() {
        assertThat(ModelDiscoveryService.hasAutoMarker("auto: true\npricing: {}\n"))
                .isTrue();
        assertThat(ModelDiscoveryService.hasAutoMarker("auto: false\n")).isFalse();
        assertThat(ModelDiscoveryService.hasAutoMarker("pricing:\n  currency: USD\n"))
                .isFalse();
        assertThat(ModelDiscoveryService.hasAutoMarker(null)).isFalse();
        assertThat(ModelDiscoveryService.hasAutoMarker("   ")).isFalse();
        // A plain string is valid YAML but not a map — operator-owned.
        assertThat(ModelDiscoveryService.hasAutoMarker("just some text")).isFalse();
        // Unparseable content is treated as operator-owned, never rewritten.
        assertThat(ModelDiscoveryService.hasAutoMarker("{unclosed")).isFalse();
    }

    private DiscoveredModelInfo pricedModel() {
        return new DiscoveredModelInfo(
                "gemini-3.8-flash", 1048576, 65535, "Google", new ModelInfo.Pricing("EUR", 0.741, 3.703, 0.074, 0.075));
    }

    // ── the discovery run driving the tests ──────────────────────────

    private ModelDiscoveryService.DiscoveryResult lastResult;

    private ModelDiscoveryService.DiscoveryResult result() {
        return lastResult;
    }

    private void runDiscoveryFor(DiscoveredModelInfo model) {
        when(tenantService.findByName(TENANT)).thenReturn(Optional.of(new TenantDocument()));
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

        lastResult = service.discoverForTenant(TENANT);
    }
}
