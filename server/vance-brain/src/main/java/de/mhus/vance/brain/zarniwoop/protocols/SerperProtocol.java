package de.mhus.vance.brain.zarniwoop.protocols;

import de.mhus.vance.brain.tools.web.ImageValidatorService;
import de.mhus.vance.brain.tools.web.YouTubeValidatorService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.toolpack.research.ProviderInstanceConfig;
import de.mhus.vance.toolpack.research.SearchModality;
import de.mhus.vance.toolpack.research.SearchProtocol;
import de.mhus.vance.toolpack.research.SearchProviderInstance;
import de.mhus.vance.toolpack.research.SearchTier;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Singleton Spring bean exposing the Serper.dev wire format. Holds the
 * cross-cutting collaborators ({@link SettingService},
 * {@link ObjectMapper}, default {@link SerperHttpClient}) and produces
 * a fresh {@link SerperInstance} per
 * {@code research.endpoint.<id>.protocol=serper} configuration.
 *
 * <p>Serper-compatible third-party endpoints (self-hosted SearXNG with
 * a Serper adapter, in-house proxies) wire through the same protocol —
 * the only thing that differs is the {@code baseUrl} in the endpoint
 * configuration.
 */
@Component
@Slf4j
public class SerperProtocol implements SearchProtocol {

    public static final String ID = "serper";

    private final SettingService settings;
    private final ObjectMapper objectMapper;
    private final SerperHttpClient http;
    private final ImageValidatorService imageValidator;
    private final YouTubeValidatorService youtubeValidator;
    private final SerperPdfHeadProbe pdfHeadProbe;

    @Autowired
    public SerperProtocol(SettingService settings,
                          ObjectMapper objectMapper,
                          ImageValidatorService imageValidator,
                          YouTubeValidatorService youtubeValidator) {
        this(settings, objectMapper,
                new SerperHttpClient.JdkSerperHttpClient(),
                imageValidator, youtubeValidator,
                new SerperPdfHeadProbe.JdkPdfHeadProbe());
    }

    /** Test-seam constructor — accepts a recording HTTP client + custom probe. */
    SerperProtocol(SettingService settings,
                   ObjectMapper objectMapper,
                   SerperHttpClient http,
                   ImageValidatorService imageValidator,
                   YouTubeValidatorService youtubeValidator,
                   SerperPdfHeadProbe pdfHeadProbe) {
        this.settings = settings;
        this.objectMapper = objectMapper;
        this.http = http;
        this.imageValidator = imageValidator;
        this.youtubeValidator = youtubeValidator;
        this.pdfHeadProbe = pdfHeadProbe;
        log.info("SerperProtocol initialised");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Serper.dev (Google SERP-as-JSON)";
    }

    @Override
    public Set<SearchModality> modalitiesSupported() {
        return Set.of(
                SearchModality.WEB,
                SearchModality.IMAGE,
                SearchModality.VIDEO,
                SearchModality.PDF);
    }

    @Override
    public Set<SearchTier> tiersSupported() {
        return Set.of(SearchTier.NORMAL, SearchTier.EXPERT);
    }

    @Override
    public SearchProviderInstance instantiate(ProviderInstanceConfig cfg) {
        if (cfg == null) {
            throw new IllegalArgumentException("cfg is required");
        }
        if (!ID.equals(cfg.protocolId())) {
            throw new IllegalArgumentException(
                    "SerperProtocol cannot instantiate config with protocol '"
                            + cfg.protocolId() + "'");
        }
        return new SerperInstance(cfg, settings, objectMapper, http,
                imageValidator, youtubeValidator, pdfHeadProbe);
    }
}
