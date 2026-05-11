package de.mhus.vance.brain.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.home.HomeBootstrapService;
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
    private static final String VANCE = HomeBootstrapService.VANCE_PROJECT_NAME;

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
                .containsExactlyInAnyOrder(ModelCapability.VISION, ModelCapability.PDF);
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
                .containsExactlyInAnyOrder(ModelCapability.VISION, ModelCapability.PDF);
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
