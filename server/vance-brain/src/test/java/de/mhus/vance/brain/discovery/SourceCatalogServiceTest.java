package de.mhus.vance.brain.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the cache layer on top of {@link SourceCatalogBuilder}.
 * Builder is mocked — we only assert the caching invariants.
 */
class SourceCatalogServiceTest {

    private SourceCatalogBuilder builder;
    private SourceCatalogService service;

    @BeforeEach
    void setUp() {
        builder = mock(SourceCatalogBuilder.class);
        service = new SourceCatalogService(builder);
    }

    @Test
    void second_call_for_same_tenant_hits_cache() {
        when(builder.build(eq("acme"), any()))
                .thenReturn(new CatalogSnapshot("first", "hash-1"));

        String s1 = service.renderForTenant("acme", null);
        String s2 = service.renderForTenant("acme", null);

        assertThat(s1).isEqualTo("first");
        assertThat(s2).isEqualTo("first");
        verify(builder, times(1)).build(eq("acme"), any());
    }

    @Test
    void different_projects_get_separate_cache_entries() {
        when(builder.build(eq("acme"), eq("proj-a")))
                .thenReturn(new CatalogSnapshot("a", "hA"));
        when(builder.build(eq("acme"), eq("proj-b")))
                .thenReturn(new CatalogSnapshot("b", "hB"));

        assertThat(service.renderForTenant("acme", "proj-a")).isEqualTo("a");
        assertThat(service.renderForTenant("acme", "proj-b")).isEqualTo("b");
        verify(builder, times(1)).build("acme", "proj-a");
        verify(builder, times(1)).build("acme", "proj-b");
    }

    @Test
    void invalidate_clears_cache_for_tenant() {
        when(builder.build(eq("acme"), any()))
                .thenReturn(new CatalogSnapshot("v1", "h1"))
                .thenReturn(new CatalogSnapshot("v2", "h2"));

        service.renderForTenant("acme", null);
        service.invalidate("acme");
        String after = service.renderForTenant("acme", null);

        assertThat(after).isEqualTo("v2");
        verify(builder, times(2)).build(eq("acme"), any());
    }

    @Test
    void invalidate_does_not_touch_other_tenants() {
        when(builder.build(eq("acme"), any()))
                .thenReturn(new CatalogSnapshot("acme-1", "h"));
        when(builder.build(eq("globex"), any()))
                .thenReturn(new CatalogSnapshot("globex-1", "h"));

        service.renderForTenant("acme", null);
        service.renderForTenant("globex", null);
        service.invalidate("acme");
        service.renderForTenant("globex", null);

        // globex stayed cached → only one build for it
        verify(builder, times(1)).build(eq("globex"), any());
    }

    @Test
    void snapshotFor_returns_full_record_with_hash() {
        when(builder.build(eq("acme"), any()))
                .thenReturn(new CatalogSnapshot("body", "deadbeef"));

        CatalogSnapshot snap = service.snapshotFor("acme", null);
        assertThat(snap.markdown()).isEqualTo("body");
        assertThat(snap.contentHash()).isEqualTo("deadbeef");
    }

    @Test
    void render_throws_when_tenantId_missing() {
        assertThatThrownBy(() -> service.renderForTenant(null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.renderForTenant("", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
