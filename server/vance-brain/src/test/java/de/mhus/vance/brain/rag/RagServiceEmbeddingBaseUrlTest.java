package de.mhus.vance.brain.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.ai.AiModelResolver;
import de.mhus.vance.brain.ai.UsageSink;
import de.mhus.vance.brain.ai.embedding.EmbeddingModelService;
import de.mhus.vance.shared.rag.RagBackend;
import de.mhus.vance.shared.rag.RagCatalogService;
import de.mhus.vance.shared.settings.SettingService;
import org.junit.jupiter.api.Test;

class RagServiceEmbeddingBaseUrlTest {

    private final SettingService settings = mock(SettingService.class);
    private final AiModelResolver resolver = mock(AiModelResolver.class);
    private final RagService service = new RagService(
            mock(RagCatalogService.class),
            mock(RagBackend.class),
            mock(EmbeddingModelService.class),
            mock(UsageSink.class),
            settings,
            resolver);

    private void configured(String url) {
        when(settings.getStringValueCascade(eq("acme"), any(), any(), eq("ai.embedding.baseUrl")))
                .thenReturn(url);
    }

    @Test
    void theTenantSettingCountsWhileOwnEndpointsAreAllowed() {
        when(resolver.ownEndpointsAllowed("acme")).thenReturn(true);
        configured(" https://8.8.8.8/v1 ");

        assertThat(service.configuredEmbeddingBaseUrl("acme")).isEqualTo("https://8.8.8.8/v1");
    }

    @Test
    void aLockedTenantFallsBackToTheProviderDefault() {
        when(resolver.ownEndpointsAllowed("acme")).thenReturn(false);
        configured("https://8.8.8.8/v1");

        assertThat(service.configuredEmbeddingBaseUrl("acme")).isNull();
    }
}
