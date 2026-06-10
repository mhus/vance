package de.mhus.vance.brain.zarniwoop.protocols;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import de.mhus.vance.brain.tools.web.ImageValidatorService;
import de.mhus.vance.brain.tools.web.YouTubeValidatorService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.toolpack.research.ProviderInstanceConfig;
import de.mhus.vance.toolpack.research.SearchModality;
import de.mhus.vance.toolpack.research.SearchProviderInstance;
import de.mhus.vance.toolpack.research.SearchTier;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SerperProtocolTest {

    private SerperProtocol protocol;

    @BeforeEach
    void setUp() {
        protocol = new SerperProtocol(
                mock(SettingService.class),
                new ObjectMapper(),
                mock(SerperHttpClient.class),
                mock(ImageValidatorService.class),
                mock(YouTubeValidatorService.class),
                mock(SerperPdfHeadProbe.class));
    }

    @Test
    void protocol_advertises_id_and_capabilities() {
        assertThat(protocol.id()).isEqualTo("serper");
        assertThat(protocol.displayName()).contains("Serper");
        assertThat(protocol.modalitiesSupported()).containsExactlyInAnyOrder(
                SearchModality.WEB, SearchModality.IMAGE,
                SearchModality.VIDEO, SearchModality.PDF);
        assertThat(protocol.tiersSupported())
                .containsExactlyInAnyOrder(SearchTier.NORMAL, SearchTier.EXPERT);
    }

    @Test
    void instantiate_produces_serper_instance_with_passed_config() {
        ProviderInstanceConfig cfg = new ProviderInstanceConfig(
                "serper-main", "serper",
                "https://google.serper.dev",
                "research.endpoint.serper-main.apiKey",
                Map.of());

        SearchProviderInstance instance = protocol.instantiate(cfg);

        assertThat(instance).isInstanceOf(SerperInstance.class);
        assertThat(instance.id()).isEqualTo("serper-main");
        assertThat(instance.modalities()).contains(SearchModality.WEB);
    }

    @Test
    void instantiate_rejects_mismatched_protocol_id() {
        ProviderInstanceConfig cfg = new ProviderInstanceConfig(
                "wiki-de", "wikipedia",
                "https://de.wikipedia.org/w/api.php",
                "research.endpoint.wiki-de.apiKey",
                Map.of());

        assertThatThrownBy(() -> protocol.instantiate(cfg))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wikipedia");
    }
}
