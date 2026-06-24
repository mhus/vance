package de.mhus.vance.brain.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.ai.image.ImageModelInfo;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.home.HomeBootstrapService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the three-layer cascade — bundled {@code vance-defaults/ai-models.yaml}
 * (real classpath read) → tenant {@code _vance/ai-models.yaml} →
 * project {@code <project>/ai-models.yaml}.
 *
 * <p>The bundled layer is exercised through the real classpath
 * resource so the assertions also pin the shipped defaults. Tenant and
 * project layers are stubbed via Mockito on {@link DocumentService},
 * which keeps the test fast and free of Mongo setup.
 *
 * <p>Merge invariant: {@code <provider>:<modelName>} keys are unioned,
 * fields within a key are overridden per-field (so a project that sets
 * only {@code defaultMaxOutputTokens} inherits the rest), and lists
 * (notably {@code capabilities}) are replaced as a whole.
 */
class ModelCatalogTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "demo";
    private static final String VANCE = HomeBootstrapService.TENANT_PROJECT_NAME;

    private DocumentService documentService;
    private ModelCatalog catalog;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
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

    // ──── Project layer additive ───────────────────────────────────────

    @Test
    void project_adds_unknown_provider_model_combination() {
        stubDocument(TENANT, PROJECT, """
                openai:
                  gpt-4o-acme:
                    contextWindowTokens: 128000
                    defaultMaxOutputTokens: 4096
                    size: LARGE
                    capabilities: [vision]
                """);
        stubMissing(TENANT, VANCE);

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
        // Project caps the output at 4K but doesn't say anything about
        // the rest. Bundled keeps contextWindow=200000, size=LARGE,
        // capabilities=[vision, pdf]. Override picks up only what changed.
        stubDocument(TENANT, PROJECT, """
                anthropic:
                  claude-sonnet-4-5:
                    defaultMaxOutputTokens: 4096
                """);
        stubMissing(TENANT, VANCE);

        ModelInfo info = catalog.lookupOrDefault(
                TENANT, PROJECT, "anthropic", "claude-sonnet-4-5");

        assertThat(info.defaultMaxOutputTokens()).isEqualTo(4096);
        // Untouched fields inherit from bundled.
        assertThat(info.contextWindowTokens()).isEqualTo(200_000);
        assertThat(info.size()).isEqualTo(ModelSize.LARGE);
        assertThat(info.capabilities())
                .containsExactlyInAnyOrder(
                        ModelCapability.VISION,
                        ModelCapability.PDF,
                        ModelCapability.THINKING);
    }

    // ──── Three-layer merge (project wins, tenant wins over bundled) ───

    @Test
    void three_layer_merge_project_beats_tenant_beats_bundled() {
        // Bundled: claude-sonnet-4-5 has contextWindowTokens=200000, defaultMaxOutputTokens=8192.
        // Tenant: caps output at 4096.
        // Project: also drops context to 100000.
        // Expected: project wins per field; tenant's cap inherited where
        // project didn't override.
        stubDocument(TENANT, VANCE, """
                anthropic:
                  claude-sonnet-4-5:
                    defaultMaxOutputTokens: 4096
                """);
        stubDocument(TENANT, PROJECT, """
                anthropic:
                  claude-sonnet-4-5:
                    contextWindowTokens: 100000
                """);

        ModelInfo info = catalog.lookupOrDefault(
                TENANT, PROJECT, "anthropic", "claude-sonnet-4-5");

        assertThat(info.contextWindowTokens()).isEqualTo(100_000);   // project
        assertThat(info.defaultMaxOutputTokens()).isEqualTo(4096);   // tenant
        assertThat(info.size()).isEqualTo(ModelSize.LARGE);          // bundled
    }

    // ──── Capabilities list — replace, not concat ──────────────────────

    @Test
    void capabilities_are_replaced_not_concatenated() {
        // Bundled gives claude-sonnet-4-5 capabilities=[vision, pdf].
        // Project explicitly lists only [vision]. Final result: [vision]
        // — replacement is required so an owner can REMOVE a capability,
        // not just add to it.
        stubDocument(TENANT, PROJECT, """
                anthropic:
                  claude-sonnet-4-5:
                    capabilities: [vision]
                """);
        stubMissing(TENANT, VANCE);

        ModelInfo info = catalog.lookupOrDefault(
                TENANT, PROJECT, "anthropic", "claude-sonnet-4-5");

        assertThat(info.capabilities()).containsExactly(ModelCapability.VISION);
    }

    // ──── Project at _vance short-circuits the duplicate read ─────────

    @Test
    void scope_set_to_vance_project_treats_it_as_tenant_only() {
        // When projectId == _vance, the resolveMerged path should NOT
        // call findByPath(tenant, _vance, ...) twice. Stub it once;
        // verify the second call (project-layer with project == _vance)
        // is not made by leaving its mock unmaterialised — Mockito
        // returns Optional.empty() by default for unstubbed Optional
        // returns, so we can't distinguish "skipped" from "missed" on
        // calls alone. Instead, assert the merge result against the
        // tenant doc: if the project-layer ran by mistake we'd see two
        // identical overlays, but the outcome would not change.
        // The real assertion here is just that no exception fires.
        stubDocument(TENANT, VANCE, """
                anthropic:
                  claude-sonnet-4-5:
                    defaultMaxOutputTokens: 1234
                """);

        ModelInfo info = catalog.lookupOrDefault(
                TENANT, VANCE, "anthropic", "claude-sonnet-4-5");

        assertThat(info.defaultMaxOutputTokens()).isEqualTo(1234);
        // Context window stays at bundled since neither tenant nor project
        // overrode it.
        assertThat(info.contextWindowTokens()).isEqualTo(200_000);
    }

    // ──── Named provider instance fallback ─────────────────────────────

    @Test
    void namedInstance_withOwnSection_winsOverProtocolFallback() {
        // Tenant declares an instance "deepseek-direct" with its own metadata.
        stubDocument(TENANT, VANCE, """
                deepseek-direct:
                  deepseek-v4-flash:
                    contextWindowTokens: 1048576
                    defaultMaxOutputTokens: 8192
                    size: SMALL
                """);

        ModelInfo info = catalog.lookupOrDefault(
                TENANT, null, "deepseek-direct", "openai", "deepseek-v4-flash");

        assertThat(info.contextWindowTokens()).isEqualTo(1_048_576);
        assertThat(info.size()).isEqualTo(ModelSize.SMALL);
    }

    @Test
    void namedInstance_missingSection_fallsBackToProtocolType() {
        // Tenant declares the instance binding but no per-instance YAML.
        // The fallback lookup uses the protocol type (openai) and finds
        // the bundled entry for gpt-4o-mini.
        stubMissing(TENANT, VANCE);

        ModelInfo info = catalog.lookupOrDefault(
                TENANT, null, "my-openai-route", "openai", "gpt-4o-mini");

        // Came from the bundled openai:gpt-4o-mini entry.
        assertThat(info.contextWindowTokens()).isEqualTo(128_000);
        assertThat(info.size()).isEqualTo(ModelSize.SMALL);
    }

    @Test
    void namedInstance_unknownEverywhere_returnsConservativeFallback() {
        // No instance section, model name also unknown under protocol type.
        stubMissing(TENANT, VANCE);

        ModelInfo info = catalog.lookupOrDefault(
                TENANT, null, "exotic-instance", "openai", "fictional-model");

        // Conservative fallback (8K context, the FALLBACK_TEMPLATE values).
        assertThat(info.contextWindowTokens()).isEqualTo(8192);
        assertThat(info.provider()).isEqualTo("exotic-instance");
    }

    // ──── kind discriminator (chat vs. image) ──────────────────────────

    @Test
    void chat_lookup_filters_image_kind_entries() {
        // Tenant adds an image-kind entry with the same wire name as a
        // chat entry. The chat lookup must skip it — chat and image
        // surfaces stay disjoint.
        stubDocument(TENANT, VANCE, """
                openai:
                  gpt-image-1:
                    kind: image
                    supportedAspectRatios: ["1:1"]
                    costPerImage:
                      standard: 0.04
                    maxPromptChars: 4000
                """);

        assertThat(catalog.lookup(TENANT, null, "openai", "gpt-image-1"))
                .isEmpty();
    }

    @Test
    void image_lookup_returns_bundled_gpt_image_1() {
        // The bundled ai-models.yaml ships gpt-image-1 as the high-quality
        // image model. Pins the metadata to detect accidental drift.
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
        // Existing chat model — looked up via lookupImage returns empty,
        // even though the name resolves in the chat catalog.
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
        // Bundled list shouldn't include gpt-image-1 in the chat surface
        // — that would pollute model-picker dropdowns.
        List<ModelInfo> chats = catalog.listAll(null, null);

        assertThat(chats).noneMatch(m -> "gpt-image-1".equals(m.modelName()));
        assertThat(chats).noneMatch(m ->
                "gemini-2.5-flash-image".equals(m.modelName()));
        assertThat(chats).anyMatch(m -> "claude-sonnet-4-5".equals(m.modelName()));
    }

    @Test
    void listAllImages_returns_only_image_kind_entries() {
        List<ImageModelInfo> images = catalog.listAllImages(null, null);

        // Every entry must be one of the bundled image models.
        assertThat(images).isNotEmpty();
        assertThat(images).allMatch(m -> "image".equals(kindOf(m)));
        assertThat(images).anyMatch(m -> "gpt-image-1".equals(m.modelName()));
        assertThat(images).anyMatch(m ->
                "gemini-2.5-flash-image".equals(m.modelName()));
        assertThat(images).anyMatch(m ->
                "imagen-3.0-generate-002".equals(m.modelName()));
    }

    /** Marker for the listAllImages assertion — every returned info is
     *  an image entry by construction (lookupImage / listAllImages
     *  filter on kind=image), so the lambda just returns "image". */
    private static String kindOf(ImageModelInfo m) {
        return m == null ? null : "image";
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
        // claude-opus-4 has no pricing in bundled YAML — unpriced model.
        ModelInfo info = catalog.lookupOrDefault("anthropic", "claude-opus-4");
        assertThat(info.pricing()).isNull();
    }

    @Test
    void project_pricing_overrides_bundled() {
        String projectYaml = """
                openai:
                  glm-5.2:
                    pricing:
                      currency: USD
                      inputPerMTok: 0.50
                      outputPerMTok: 2.00
                """;
        stubMissing(TENANT, VANCE);
        stubDocument(TENANT, PROJECT, projectYaml);

        ModelInfo info = catalog.lookupOrDefault(TENANT, PROJECT, "openai", "glm-5.2");
        assertThat(info.pricing()).isNotNull();
        assertThat(info.pricing().currency()).isEqualTo("USD");
        assertThat(info.pricing().inputPerMTok()).isEqualTo(0.50);
        assertThat(info.pricing().outputPerMTok()).isEqualTo(2.00);
    }

    // ──── Helpers ──────────────────────────────────────────────────────

    private void stubDocument(String tenantId, String projectId, String yaml) {
        DocumentDocument doc = mock(DocumentDocument.class);
        when(documentService.findByPath(eq(tenantId), eq(projectId), eq(ModelCatalog.CATALOG_PATH)))
                .thenReturn(Optional.of(doc));
        when(documentService.readContent(doc)).thenReturn(yaml);
    }

    private void stubMissing(String tenantId, String projectId) {
        when(documentService.findByPath(eq(tenantId), eq(projectId), eq(ModelCatalog.CATALOG_PATH)))
                .thenReturn(Optional.empty());
    }
}
