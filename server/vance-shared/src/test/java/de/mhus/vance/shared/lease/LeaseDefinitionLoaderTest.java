package de.mhus.vance.shared.lease;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.document.DocumentService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The four-layer field merge of {@link LeaseDefinitionLoader} — the
 * ownership table of {@code planning/lease-system.md} §4 is the contract
 * under test: a tenant cannot raise the global ceiling, a project cannot
 * pick its own counter or tenant cap, and absent fields never shadow an
 * inner layer's value.
 */
class LeaseDefinitionLoaderTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "proj-1";

    private DocumentService documentService;
    private LeaseDefinitionLoader loader;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        // Classpath layer absent unless a test provides one.
        when(documentService.readBundledResource(any())).thenReturn(Optional.empty());
        when(documentService.readContent(any())).thenReturn("");
        loader = new LeaseDefinitionLoader(documentService);
    }

    private void layer(String tenantId, String projectId, String yaml) {
        var doc = mock(de.mhus.vance.shared.document.DocumentDocument.class);
        when(documentService.findByPath(eq(tenantId), eq(projectId), any())).thenReturn(Optional.of(doc));
        when(documentService.readContent(doc)).thenReturn(yaml);
    }

    @Test
    void load_noLayerDefinesTheName_isEmpty() {
        assertThat(loader.load(TENANT, PROJECT, "tex")).isEmpty();
    }

    @Test
    void load_systemLayer_appliesGlobalAndTenantCap() {
        layer("_vance", "_tenant", "levels: { global: 6, tenant: 3 }");

        LeaseDefinition def = loader.load(TENANT, PROJECT, "tex").orElseThrow();

        assertThat(def.maxGlobal()).isEqualTo(6L);
        assertThat(def.maxTenant()).isEqualTo(3L);
        assertThat(def.resourceKey()).isEqualTo("tex");
    }

    @Test
    void merge_tenantLayerOverridesTenantCapButCannotRaiseGlobal() {
        layer("_vance", "_tenant", "levels: { global: 6, tenant: 3 }");
        layer("acme", "_tenant", "levels: { global: 99, tenant: 5 }");

        LeaseDefinition def = loader.load(TENANT, PROJECT, "tex").orElseThrow();

        assertThat(def.maxGlobal())
                .as("the global cap is the ceiling of all tenants — a tenant layer cannot raise it")
                .isEqualTo(6L);
        assertThat(def.maxTenant()).isEqualTo(5L);
    }

    @Test
    void merge_projectLayerOwnsOnlyItsOwnFields() {
        layer("_vance", "_tenant", "levels: { global: 6, tenant: 3 }\nresource: tex2pdf");
        layer("acme", "proj-1", "levels: { project: 2, tenant: 9, global: 99 }\nresource: private-counter");

        LeaseDefinition def = loader.load(TENANT, PROJECT, "tex").orElseThrow();

        assertThat(def.maxProject()).isEqualTo(2L);
        assertThat(def.maxTenant())
                .as("project layer has no say in the tenant cap")
                .isEqualTo(3L);
        assertThat(def.maxGlobal()).isEqualTo(6L);
        assertThat(def.resourceKey())
                .as("a project must not land on a private counter by accident")
                .isEqualTo("tex2pdf");
    }

    @Test
    void merge_ttlFromTenantLayer_beatsSystemDefault() {
        layer("_vance", "_tenant", "levels: { global: 1 }\nttlSeconds: 120");
        layer("acme", "_tenant", "ttlSeconds: 300");

        LeaseDefinition def = loader.load(TENANT, PROJECT, "tex").orElseThrow();

        assertThat(def.ttlSecondsOrDefault()).isEqualTo(300L);
    }

    @Test
    void merge_userLevel_appliesAcrossAllLayers() {
        layer("acme", "proj-1", "levels: { user: 1 }");

        LeaseDefinition def = loader.load(TENANT, PROJECT, "tex").orElseThrow();

        assertThat(def.maxUser()).isEqualTo(1L);
        assertThat(def.hasAnyCap()).isTrue();
    }

    @Test
    void load_isCachedPerScope_untilInvalidated() {
        layer("acme", "_tenant", "levels: { tenant: 1 }");

        loader.load(TENANT, PROJECT, "tex");
        loader.load(TENANT, PROJECT, "tex");

        // The second load is a cache hit — the tenant layer was read once.
        verify(documentService, times(1)).findByPath(eq("acme"), eq("_tenant"), any());

        loader.invalidate(TENANT, "_tenant", "tex");
        loader.load(TENANT, PROJECT, "tex");

        // After the invalidate the cache is bypassed and the layer is re-read.
        verify(documentService, times(2)).findByPath(eq("acme"), eq("_tenant"), any());
    }

    @Test
    void invalidate_systemLayerWrite_dropsTheNameForEveryScope() {
        layer("acme", "_tenant", "levels: { tenant: 1 }");
        loader.load(TENANT, PROJECT, "tex");
        loader.load("other-tenant", "other-project", "tex");

        // Both scopes served the cache on their second load — one read each.
        verify(documentService, times(1)).findByPath(eq("acme"), eq("_tenant"), any());
        verify(documentService, times(1)).findByPath(eq("other-tenant"), eq("_tenant"), any());

        loader.invalidate("_vance", "_tenant", "tex");
        loader.load(TENANT, PROJECT, "tex");
        loader.load("other-tenant", "other-project", "tex");

        // A system-layer write drops the name for every scope: both scopes
        // re-read their layers instead of serving the cache.
        verify(documentService, times(2)).findByPath(eq("acme"), eq("_tenant"), any());
        verify(documentService, times(2)).findByPath(eq("other-tenant"), eq("_tenant"), any());
    }

    @Test
    void readLayers_malformedYaml_isSkippedWithTheOthersStillMerging() {
        layer("acme", "_tenant", "{ unbalanced");
        layer("acme", "proj-1", "levels: { project: 1 }");

        LeaseDefinition def = loader.load(TENANT, PROJECT, "tex").orElseThrow();

        assertThat(def.resourceKey())
                .as("malformed tenant layer contributes nothing")
                .isEqualTo("tex");
        assertThat(def.maxProject()).isEqualTo(1L);
    }

    @Test
    void readLayers_classpathBundledDefault_isTheOutermostLayer() {
        when(documentService.readBundledResource(any()))
                .thenReturn(Optional.of("levels: { global: 6, tenant: 3 }\nttlSeconds: 120"));

        LeaseDefinition def = loader.load(TENANT, PROJECT, "tex").orElseThrow();

        assertThat(def.maxGlobal()).isEqualTo(6L);
        assertThat(def.ttlSecondsOrDefault()).isEqualTo(120L);
        // The classpath layer never short-circuits the merge: the Mongo
        // layers are still read so inner overrides keep working.
        verify(documentService, atLeastOnce()).findByPath(any(), any(), any());
    }
}
