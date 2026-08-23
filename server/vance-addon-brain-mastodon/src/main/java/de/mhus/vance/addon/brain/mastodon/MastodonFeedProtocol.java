package de.mhus.vance.addon.brain.mastodon;

import de.mhus.vance.brain.centauri.protocols.CentauriHttpClient;
import de.mhus.vance.toolpack.feed.FeedInstanceConfig;
import de.mhus.vance.toolpack.feed.FeedProtocol;
import de.mhus.vance.toolpack.feed.FeedSourceInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Public Mastodon timelines as a Centauri source, one instance per server.
 *
 * <pre>
 * # _vance/config/feeds/mastodon-1.yaml
 * protocol: mastodon
 * baseUrl:  https://mstdn.social
 * apiKey:   (optional app token)
 * </pre>
 *
 * <p>One server is one source, the way one language wiki is one Wikipedia
 * source. Several servers in one feed make a federated stream, and the merge
 * already knows how to mix them.
 *
 * <p><b>Endpoint choice.</b> The REST timelines, not {@code /api/v1/streaming}:
 * streaming is push with no {@code ?max_id=&limit=}, so consuming it would mean
 * buffering a firehose. The REST endpoints page with {@code max_id}/{@code
 * min_id}, which is literally Centauri's cursor model — and {@code max_id} is
 * <b>exclusive</b> (measured), so the SPI's default {@code cursorAfter} works
 * unchanged.
 *
 * <p><b>No client library.</b> BigBone ({@code io.github.pattafeufeu:bigbone})
 * would bring kotlin-stdlib, okhttp and kotlinx-serialization for two GETs, and
 * it models the whole client surface — posting, boosting, OAuth — which is
 * exactly what {@code planning/centauri-feeds.md} §11 keeps out of this SPI.
 *
 * <p><b>Access.</b> Both timelines are documented „Public. Requires app token
 * + {@code read:statuses} if the instance has disabled public preview." That is
 * per <em>endpoint</em>, not per instance: mastodon.social serves the hashtag
 * timeline and refuses the public one. An app token is not a person, so the
 * credential-optional model of §11 stays intact.
 */
@Component
public class MastodonFeedProtocol implements FeedProtocol {

    public static final String ID = "mastodon";

    private final CentauriHttpClient http;
    private final ObjectMapper mapper;

    @Autowired
    public MastodonFeedProtocol(ObjectMapper mapper) {
        this(new CentauriHttpClient.JdkCentauriHttpClient(), mapper);
    }

    MastodonFeedProtocol(CentauriHttpClient http, ObjectMapper mapper) {
        this.http = http;
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Mastodon timelines";
    }

    @Override
    public FeedSourceInstance instantiate(FeedInstanceConfig cfg) {
        if (cfg.baseUrl().isBlank()) {
            throw new IllegalArgumentException("endpoint '" + cfg.instanceId()
                    + "' needs a baseUrl, e.g. https://mstdn.social");
        }
        return new MastodonFeedInstance(cfg, http, mapper);
    }
}
