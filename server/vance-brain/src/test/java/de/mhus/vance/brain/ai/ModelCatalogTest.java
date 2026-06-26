package de.mhus.vance.brain.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.ai.image.ImageModelInfo;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.home.HomeBootstrapService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the per-file model catalog with atomic-swap refresh.
 *
 * <p>Bundled layer is read from the real classpath under
 * {@code vance-defaults/_vance/model/**}; tenant/project layers are
 * faked by stubbing {@link DocumentService#findAllByPathPrefix} to
 * return mock {@link DocumentDocument}s with the path and YAML
 * content the test wants visible at that scope. Tests call
 * {@link ModelCatalog#refresh()} after stubbing so the snapshot
 * picks up the fixtures.
 */
class ModelCatalogTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "demo";
    private static final String VANCE_TENANT_PROJECT = HomeBootstrapService.TENANT_PROJECT_NAME;

    private DocumentService documentService;
    private ModelCatalog catalog;
    private List<DocumentDocument> overrideDocs;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        overrideDocs = new ArrayList<>();
        // Default: no override docs anywhere — the catalog falls back
        // to bundled-only.
        when(documentService.findAllByPathPrefix(ModelCatalog.MODEL_PATH_PREFIX))
                .thenReturn(overrideDocs);
        catalog = new ModelCatalog(documentService);
    }

    // ──── Bundled-only behaviour (no scope) ────────────────────────────

    @Test
    void bundled_known_model_returns_canonical_metadata() {
        ModelInfo info = catalog.lookupOrDefault("anthropic", "claude-sonnet-4-5");

        assertThat(info.provider()).isEqualTo("anthropic");
        assertThat(info.modelName()).isEqualTo("claude-sonnet-4-5");
        assertThat(info.contextWindowTokens()).isEqualTo(200_000);
        assertThat(info.defaultMaxOutputTokens()).isEqualTo(8192);
        assertThat(info.size()).isEqualTo(ModelSize.LARGE);
        assertThat(info.capabilities())
                .containsExactlyInAnyOrder(
                        ModelCapability.VISION,
                        ModelCapability.PDF,
                        ModelCapability.THINKING);
    }

    @Test
    void bundled_unknown_model_returns_fallback_via_lookupOrDefault() {
        ModelInfo info = catalog.lookupOrDefault("nonexistent", "ghost-1");

        assertThat(info.provider()).isEqualTo("nonexistent");
        assertThat(info.modelName()).isEqualTo("ghost-1");
        assertThat(info.contextWindowTokens()).isEqualTo(8192);
        assertThat(info.capabilities()).isEmpty();
    }

    @Test
    void bundled_unknown_model_returns_empty_via_lookup() {
        assertThat(catalog.lookup("nonexistent", "ghost-1")).isEmpty();
    }

    @Test
    void null_provider_or_model_returns_empty() {
        assertThat(catalog.lookup(null, null, null, "claude-sonnet-4-5")).isEmpty();
        assertThat(catalog.lookup(null, null, "anthropic", null)).isEmpty();
    }

    // ──── Wire-name encoded as nested directory (HF-style) ─────────────

    @Test
    void bundled_hf_style_wire_name_loaded_from_nested_directory() {
        // _vance/model/openai/google/gemma-4-31B-it.yaml — wire name
        // recovered from the nested directory path.
        ModelInfo info = catalog.lookupOrDefault("openai", "google/gemma-4-31B-it");
        assertThat(info.contextWindowTokens()).isEqualTo(131_072);
        assertThat(info.size()).isEqualTo(ModelSize.SMALL);
    }

    @Test
    void bundled_explicit_wireName_field_carries_colon_tagged_name() {
        // _vance/model/ollama/qwen3-30b.yaml ships `wireName: "qwen3:30b"`
        // to encode the Ollama tag style without breaking filename safety.
        ModelInfo info = catalog.lookupOrDefault("ollama", "qwen3:30b");
        assertThat(info.contextWindowTokens()).isEqualTo(131_072);
        assertThat(info.stripThinkTags()).isTrue();
    }

    // ──── Project layer additive ───────────────────────────────────────

    @Test
    void project_adds_unknown_provider_model_combination() {
        stubModelDoc(TENANT, PROJECT, "openai/gpt-4o-acme.yaml", """
                contextWindowTokens: 128000
                defaultMaxOutputTokens: 4096
                size: LARGE
                capabilities: [vision]
                """);
        catalog.refresh();

        ModelInfo info = catalog.lookupOrDefault(
                TENANT, PROJECT, "openai", "gpt-4o-acme");

        assertThat(info.contextWindowTokens()).isEqualTo(128_000);
        assertThat(info.defaultMaxOutputTokens()).isEqualTo(4096);
        assertThat(info.size()).isEqualTo(ModelSize.LARGE);
        assertThat(info.capabilities()).containsExactly(ModelCapability.VISION);
    }

    // ──── Per-field override ───────────────────────────────────────────

    @Test
    void project_overrides_single_field_inherits_rest_from_bundled() {
        // Project caps output at 4K but says nothing else. Bundled
        // keeps contextWindow=200000, size=LARGE, capabilities=[vision,pdf,thinking].
        stubModelDoc(TENANT, PROJECT, "anthropic/claude-sonnet-4-5.yaml", """
                defaultMaxOutputTokens: 4096
                """);
        catalog.refresh();

        ModelInfo info = catalog.lookupOrDefault(
                TENANT, PROJECT, "anthropic", "claude-sonnet-4-5");

        assertThat(info.defaultMaxOutputTokens()).isEqualTo(4096);
        assertThat(info.contextWindowTokens()).isEqualTo(200_000);
        assertThat(info.size()).isEqualTo(ModelSize.LARGE);
        assertThat(info.capabilities())
                .containsExactlyInAnyOrder(
                        ModelCapability.VISION,
                        ModelCapability.PDF,
                        ModelCapability.THINKING);
    }

    // ──── Three-layer merge (project beats tenant beats bundled) ───────

    @Test
    void three_layer_merge_project_beats_tenant_beats_bundled() {
        stubModelDoc(TENANT, VANCE_TENANT_PROJECT, "anthropic/claude-sonnet-4-5.yaml", """
                defaultMaxOutputTokens: 4096
                """);
        stubModelDoc(TENANT, PROJECT, "anthropic/claude-sonnet-4-5.yaml", """
                contextWindowTokens: 100000
                """);
        catalog.refresh();

        ModelInfo info = catalog.lookupOrDefault(
                TENANT, PROJECT, "anthropic", "claude-sonnet-4-5");

        assertThat(info.contextWindowTokens()).isEqualTo(100_000);   // project
        assertThat(info.defaultMaxOutputTokens()).isEqualTo(4096);   // tenant
        assertThat(info.size()).isEqualTo(ModelSize.LARGE);          // bundled
    }

    // ──── Capabilities list — replace, not concat ──────────────────────

    @Test
    void capabilities_are_replaced_not_concatenated() {
        // Bundled gives claude-sonnet-4-5 capabilities=[vision, pdf, thinking].
        // Project explicitly lists only [vision]. Final result: [vision].
        stubModelDoc(TENANT, PROJECT, "anthropic/claude-sonnet-4-5.yaml", """
                capabilities: [vision]
                """);
        catalog.refresh();

        ModelInfo info = catalog.lookupOrDefault(
                TENANT, PROJECT, "anthropic", "claude-sonnet-4-5");

        assertThat(info.capabilities()).containsExactly(ModelCapability.VISION);
    }

    // ──── System tenant layer (global before per-tenant) ───────────────

    @Test
    void systemTenant_overrides_bundled_for_all_tenants() {
        // _vance / _tenant — global maintainer-layer.
        stubModelDoc(ModelCatalog.MODEL_PATH_PREFIX.contains("_vance") ? "_vance" : "_vance",
                VANCE_TENANT_PROJECT,
                "anthropic/claude-sonnet-4-5.yaml", """
                defaultMaxOutputTokens: 999
                """);
        catalog.refresh();

        // Tenant A sees the global override.
        ModelInfo info = catalog.lookupOrDefault(
                "tenant-a", null, "anthropic", "claude-sonnet-4-5");
        assertThat(info.defaultMaxOutputTokens()).isEqualTo(999);

        // Tenant B sees the same global override.
        ModelInfo infoB = catalog.lookupOrDefault(
                "tenant-b", null, "anthropic", "claude-sonnet-4-5");
        assertThat(infoB.defaultMaxOutputTokens()).isEqualTo(999);
    }

    // ──── Named provider instance fallback ─────────────────────────────

    @Test
    void namedInstance_withOwnSection_winsOverProtocolFallback() {
        // Tenant declares an instance "deepseek-direct" with its own metadata.
        stubModelDoc(TENANT, VANCE_TENANT_PROJECT,
                "deepseek-direct/deepseek-v4-flash.yaml", """
                contextWindowTokens: 1048576
                defaultMaxOutputTokens: 8192
                size: SMALL
                """);
        catalog.refresh();

        ModelInfo info = catalog.lookupOrDefault(
                TENANT, null, "deepseek-direct", "openai", "deepseek-v4-flash");

        assertThat(info.contextWindowTokens()).isEqualTo(1_048_576);
        assertThat(info.size()).isEqualTo(ModelSize.SMALL);
    }

    @Test
    void namedInstance_missingSection_fallsBackToProtocolType() {
        // No per-instance YAML — the lookup falls back to the protocol
        // type (openai) and finds the bundled entry for gpt-4o-mini.
        ModelInfo info = catalog.lookupOrDefault(
                TENANT, null, "my-openai-route", "openai", "gpt-4o-mini");

        assertThat(info.contextWindowTokens()).isEqualTo(128_000);
        assertThat(info.size()).isEqualTo(ModelSize.SMALL);
    }

    @Test
    void namedInstance_unknownEverywhere_returnsConservativeFallback() {
        ModelInfo info = catalog.lookupOrDefault(
                TENANT, null, "exotic-instance", "openai", "fictional-model");

        assertThat(info.contextWindowTokens()).isEqualTo(8192);
        assertThat(info.provider()).isEqualTo("exotic-instance");
    }

    // ──── kind discriminator (chat vs. image) ──────────────────────────

    @Test
    void chat_lookup_filters_image_kind_entries() {
        // Tenant adds an image-kind entry with the same wire name as a
        // chat entry. The chat lookup must skip it.
        stubModelDoc(TENANT, VANCE_TENANT_PROJECT, "openai/gpt-image-1.yaml", """
                kind: image
                supportedAspectRatios: ["1:1"]
                costPerImage:
                  standard: 0.04
                maxPromptChars: 4000
                """);
        catalog.refresh();

        assertThat(catalog.lookup(TENANT, null, "openai", "gpt-image-1")).isEmpty();
    }

    @Test
    void image_lookup_returns_bundled_gpt_image_1() {
        ImageModelInfo info = catalog
                .lookupImage(null, null, "openai", "gpt-image-1")
                .orElseThrow();

        assertThat(info.provider()).isEqualTo("openai");
        assertThat(info.modelName()).isEqualTo("gpt-image-1");
        assertThat(info.maxPromptChars()).isEqualTo(4000);
        assertThat(info.timeoutSeconds()).isEqualTo(360);
        assertThat(info.supportedAspectRatios())
                .containsExactlyInAnyOrder("1:1", "16:9", "9:16", "4:3", "3:4");
        assertThat(info.costFor("standard")).isEqualTo(0.04);
        assertThat(info.costFor("hd")).isEqualTo(0.08);
    }

    @Test
    void image_lookup_returns_bundled_gemini_flash_image() {
        ImageModelInfo info = catalog
                .lookupImage(null, null, "gemini", "gemini-2.5-flash-image")
                .orElseThrow();

        assertThat(info.timeoutSeconds()).isEqualTo(90);
        assertThat(info.maxPromptChars()).isEqualTo(480);
        assertThat(info.costFor("standard")).isEqualTo(0.005);
        assertThat(info.costFor("hd")).isNull();
    }

    @Test
    void image_lookup_filters_chat_kind_entries() {
        assertThat(catalog.lookupImage(null, null, "anthropic", "claude-sonnet-4-5"))
                .isEmpty();
    }

    @Test
    void image_lookup_unknown_model_returns_empty() {
        assertThat(catalog.lookupImage(null, null, "openai", "ghost-image-9000"))
                .isEmpty();
    }

    @Test
    void listAll_excludes_image_kind_entries() {
        List<ModelInfo> chats = catalog.listAll(null, null);

        assertThat(chats).noneMatch(m -> "gpt-image-1".equals(m.modelName()));
        assertThat(chats).noneMatch(m ->
                "gemini-2.5-flash-image".equals(m.modelName()));
        assertThat(chats).anyMatch(m -> "claude-sonnet-4-5".equals(m.modelName()));
    }

    @Test
    void listAllImages_returns_only_image_kind_entries() {
        List<ImageModelInfo> images = catalog.listAllImages(null, null);

        assertThat(images).isNotEmpty();
        assertThat(images).anyMatch(m -> "gpt-image-1".equals(m.modelName()));
        assertThat(images).anyMatch(m ->
                "gemini-2.5-flash-image".equals(m.modelName()));
        assertThat(images).anyMatch(m ->
                "imagen-3.0-generate-002".equals(m.modelName()));
    }

    // ──── Pricing block parsing ────────────────────────────────────────

    @Test
    void bundled_glm_5_2_has_cortecs_pricing() {
        ModelInfo info = catalog.lookupOrDefault("openai", "glm-5.2");
        assertThat(info.pricing()).isNotNull();
        assertThat(info.pricing().currency()).isEqualTo("EUR");
        assertThat(info.pricing().inputPerMTok()).isEqualTo(0.355);
        assertThat(info.pricing().outputPerMTok()).isEqualTo(1.775);
        assertThat(info.pricing().cacheReadPerMTok()).isNull();
        assertThat(info.pricing().cacheWritePerMTok()).isNull();
    }

    @Test
    void bundled_sonnet_4_5_has_anthropic_pricing_including_cache() {
        ModelInfo info = catalog.lookupOrDefault("anthropic", "claude-sonnet-4-5");
        assertThat(info.pricing()).isNotNull();
        assertThat(info.pricing().currency()).isEqualTo("USD");
        assertThat(info.pricing().inputPerMTok()).isEqualTo(3.00);
        assertThat(info.pricing().outputPerMTok()).isEqualTo(15.00);
        assertThat(info.pricing().cacheReadPerMTok()).isEqualTo(0.30);
        assertThat(info.pricing().cacheWritePerMTok()).isEqualTo(3.75);
    }

    @Test
    void model_without_pricing_block_returns_null() {
        // claude-opus-4 ships without pricing block — unpriced model.
        ModelInfo info = catalog.lookupOrDefault("anthropic", "claude-opus-4");
        assertThat(info.pricing()).isNull();
    }

    @Test
    void project_pricing_overrides_bundled() {
        stubModelDoc(TENANT, PROJECT, "openai/glm-5.2.yaml", """
                pricing:
                  currency: USD
                  inputPerMTok: 0.50
                  outputPerMTok: 2.00
                """);
        catalog.refresh();

        ModelInfo info = catalog.lookupOrDefault(TENANT, PROJECT, "openai", "glm-5.2");
        assertThat(info.pricing()).isNotNull();
        assertThat(info.pricing().currency()).isEqualTo("USD");
        assertThat(info.pricing().inputPerMTok()).isEqualTo(0.50);
        assertThat(info.pricing().outputPerMTok()).isEqualTo(2.00);
    }

    // ──── Atomic-swap refresh semantics ────────────────────────────────

    @Test
    void refresh_returns_counters_with_bundled_load() {
        ModelCatalog.RefreshResult result = catalog.refresh();
        assertThat(result.bundledModelsLoaded()).isGreaterThan(50);
        assertThat(result.bundledProvidersLoaded()).isGreaterThanOrEqualTo(5);
        assertThat(result.durationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void refresh_picks_up_newly_added_override_doc() {
        // Round 1: no overrides → bundled value.
        ModelInfo before = catalog.lookupOrDefault(
                TENANT, PROJECT, "anthropic", "claude-sonnet-4-5");
        assertThat(before.contextWindowTokens()).isEqualTo(200_000);

        // Round 2: add an override and re-refresh.
        stubModelDoc(TENANT, PROJECT, "anthropic/claude-sonnet-4-5.yaml", """
                contextWindowTokens: 50000
                """);
        catalog.refresh();

        ModelInfo after = catalog.lookupOrDefault(
                TENANT, PROJECT, "anthropic", "claude-sonnet-4-5");
        assertThat(after.contextWindowTokens()).isEqualTo(50_000);
    }

    // ──── Provider metadata (_provider.yaml) ───────────────────────────

    @Test
    void provider_metadata_loaded_from_bundled_provider_yaml() {
        // Bundled anthropic/_provider.yaml carries wireType=anthropic.
        var provider = catalog.lookupProvider(null, null, "anthropic").orElseThrow();
        assertThat(provider.get("wireType")).isEqualTo("anthropic");
        assertThat(provider.get("displayName")).isEqualTo("Anthropic");
    }

    @Test
    void provider_metadata_override_merges_per_field() {
        // Tenant overrides only the baseUrl of the openai provider for
        // their private vLLM endpoint.
        stubModelDoc(TENANT, VANCE_TENANT_PROJECT, "openai/_provider.yaml", """
                baseUrl: https://vllm.internal/v1
                """);
        catalog.refresh();

        var provider = catalog.lookupProvider(TENANT, null, "openai").orElseThrow();
        assertThat(provider.get("baseUrl")).isEqualTo("https://vllm.internal/v1");
        assertThat(provider.get("wireType")).isEqualTo("openai");   // inherited
    }

    // ──── Auto layer (_vance/model-auto/**) ────────────────────────────

    @Test
    void auto_doc_is_visible_in_lookup_without_manual() {
        // Discovery wrote a doc with just existence + context-window;
        // no manual override at this scope.
        stubAutoModelDoc(TENANT, VANCE_TENANT_PROJECT,
                "openai/gpt-5-turbo.yaml", """
                contextWindowTokens: 400000
                kind: chat
                discoveredBy: discovery-job
                """);
        catalog.refresh();

        ModelInfo info = catalog.lookupOrDefault(
                TENANT, null, "openai", "gpt-5-turbo");
        assertThat(info.contextWindowTokens()).isEqualTo(400_000);
    }

    @Test
    void manual_beats_auto_at_same_scope() {
        // Discovery says context-window=200000; operator manually pinned
        // it to 500000 in the manual layer. Manual wins.
        stubAutoModelDoc(TENANT, VANCE_TENANT_PROJECT,
                "openai/gpt-5.yaml", """
                contextWindowTokens: 200000
                """);
        stubModelDoc(TENANT, VANCE_TENANT_PROJECT,
                "openai/gpt-5.yaml", """
                contextWindowTokens: 500000
                """);
        catalog.refresh();

        ModelInfo info = catalog.lookupOrDefault(
                TENANT, null, "openai", "gpt-5");
        assertThat(info.contextWindowTokens()).isEqualTo(500_000);
    }

    @Test
    void auto_and_manual_merge_per_field_within_one_scope() {
        // Discovery writes only contextWindowTokens; pricing is in the
        // manual layer at the same scope. Final view has both.
        stubAutoModelDoc(TENANT, VANCE_TENANT_PROJECT,
                "openai/new-model.yaml", """
                contextWindowTokens: 128000
                kind: chat
                """);
        stubModelDoc(TENANT, VANCE_TENANT_PROJECT,
                "openai/new-model.yaml", """
                size: SMALL
                pricing:
                  currency: USD
                  inputPerMTok: 1.0
                  outputPerMTok: 4.0
                """);
        catalog.refresh();

        ModelInfo info = catalog.lookupOrDefault(
                TENANT, null, "openai", "new-model");
        assertThat(info.contextWindowTokens()).isEqualTo(128_000);     // auto
        assertThat(info.size()).isEqualTo(ModelSize.SMALL);            // manual
        assertThat(info.pricing()).isNotNull();
        assertThat(info.pricing().inputPerMTok()).isEqualTo(1.0);      // manual
    }

    @Test
    void project_auto_beats_tenant_manual_for_innermost_wins() {
        // Cascade ordering: project-* beats tenant-* regardless of layer
        // kind. Bundled claude-sonnet-4-5 has contextWindow=200000;
        // tenant-manual sets it to 100000; project-auto sets to 50000.
        // Lookup at (tenant, project): project-auto wins.
        stubModelDoc(TENANT, VANCE_TENANT_PROJECT,
                "anthropic/claude-sonnet-4-5.yaml", """
                contextWindowTokens: 100000
                """);
        stubAutoModelDoc(TENANT, PROJECT,
                "anthropic/claude-sonnet-4-5.yaml", """
                contextWindowTokens: 50000
                """);
        catalog.refresh();

        ModelInfo info = catalog.lookupOrDefault(
                TENANT, PROJECT, "anthropic", "claude-sonnet-4-5");
        assertThat(info.contextWindowTokens()).isEqualTo(50_000);
    }

    // ──── Helpers ──────────────────────────────────────────────────────

    /**
     * Add a stub <b>manual</b> model document to the in-memory override
     * set. Pass {@code relPath} as the path under
     * {@code _vance/model/} — e.g. {@code "anthropic/claude-sonnet-4-5.yaml"}.
     * Call {@link ModelCatalog#refresh()} afterwards for the change to
     * take effect.
     */
    private void stubModelDoc(String tenantId, String projectId, String relPath, String yamlBody) {
        stubDocAtPrefix(ModelCatalog.MODEL_PATH_PREFIX,
                tenantId, projectId, relPath, yamlBody);
    }

    /**
     * Add a stub <b>auto</b> (discovery-written) model document. Same
     * shape as {@link #stubModelDoc} but the document path lives under
     * {@code _vance/model-auto/}.
     */
    private void stubAutoModelDoc(
            String tenantId, String projectId, String relPath, String yamlBody) {
        stubDocAtPrefix(ModelCatalog.AUTO_MODEL_PATH_PREFIX,
                tenantId, projectId, relPath, yamlBody);
    }

    private void stubDocAtPrefix(
            String pathPrefix, String tenantId, String projectId,
            String relPath, String yamlBody) {
        DocumentDocument doc = mock(DocumentDocument.class);
        lenient().when(doc.getTenantId()).thenReturn(tenantId);
        lenient().when(doc.getProjectId()).thenReturn(projectId);
        lenient().when(doc.getPath()).thenReturn(pathPrefix + relPath);
        lenient().when(documentService.readContent(eq(doc))).thenReturn(yamlBody);
        overrideDocs.add(doc);
        // findAllByPathPrefix gets called once per prefix in refresh();
        // returning the full mixed list is fine — the catalog itself
        // filters by path-prefix to assign each doc to the right layer.
        when(documentService.findAllByPathPrefix(any())).thenReturn(overrideDocs);
    }
}
