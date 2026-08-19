package de.mhus.vance.brain.centauri.protocols;

import de.mhus.vance.toolpack.feed.FeedInstanceConfig;
import de.mhus.vance.toolpack.feed.FeedProtocol;
import de.mhus.vance.toolpack.feed.FeedSourceInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * The protocol for sources that speak the contract {@code vance-ode-centauri}
 * defines.
 *
 * <p>Called {@code ode} and not {@code hrafnagud}: the contract belongs to the
 * library any foreign application can embed, and Hrafnagud is the first source
 * to speak it rather than its measure. A protocol named after its first
 * consumer would have to be renamed or duplicated for the second.
 *
 * <p>Configuration per endpoint:
 * <pre>
 * centauri.endpoint.hrafnagud-main.protocol = ode
 * centauri.endpoint.hrafnagud-main.baseUrl  = https://hrafnagud.example
 * centauri.endpoint.hrafnagud-main.apiKey   = (PASSWORD, optional)
 * centauri.endpoint.hrafnagud-main.feedPath = /ode/feed   (optional)
 * </pre>
 * {@code feedPath} exists because the serving side can move the path
 * ({@code vance.ode.centauri.path}); a source that does so has to say where.
 */
@Component
public class OdeFeedProtocol implements FeedProtocol {

    public static final String ID = "ode";

    /** Default of {@code vance.ode.centauri.path} on the serving side. */
    static final String DEFAULT_FEED_PATH = "/ode/feed";

    static final String EXTRA_FEED_PATH = "feedPath";

    private final CentauriHttpClient http;
    private final ObjectMapper mapper;

    /**
     * Production wiring. Annotated because the test seam below makes this a
     * two-constructor bean, and Spring will not guess which one is meant.
     */
    @Autowired
    public OdeFeedProtocol(ObjectMapper mapper) {
        this(new CentauriHttpClient.JdkCentauriHttpClient(), mapper);
    }

    OdeFeedProtocol(CentauriHttpClient http, ObjectMapper mapper) {
        this.http = http;
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Ode feed source";
    }

    @Override
    public FeedSourceInstance instantiate(FeedInstanceConfig cfg) {
        if (cfg.baseUrl().isBlank()) {
            throw new IllegalArgumentException(
                    "endpoint '" + cfg.instanceId() + "' needs a baseUrl");
        }
        return new OdeFeedInstance(cfg, http, mapper);
    }
}
