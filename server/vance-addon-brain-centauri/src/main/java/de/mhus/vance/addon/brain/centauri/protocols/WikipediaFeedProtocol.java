package de.mhus.vance.addon.brain.centauri.protocols;

import de.mhus.vance.brain.centauri.protocols.CentauriHttpClient;
import de.mhus.vance.toolpack.feed.FeedInstanceConfig;
import de.mhus.vance.toolpack.feed.FeedProtocol;
import de.mhus.vance.toolpack.feed.FeedSourceInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Recent changes of a MediaWiki wiki, one instance per wiki.
 *
 * <p>One wiki is one language, so the language dimension falls out of the
 * configuration rather than being filtered: point two endpoints at
 * {@code de.wikipedia.org} and {@code en.wikipedia.org} and a feed over both
 * carries both languages, each entry tagged.
 *
 * <p>Note the endpoint. The obvious candidate is
 * {@code stream.wikimedia.org/v2/stream/recentchange}, but that is Server-Sent
 * Events: a push firehose with no {@code ?cursor=&limit=}, so becoming a source
 * would mean buffering it — more work than this, not less. The Action API pages
 * properly, and its {@code rccontinue} is literally
 * {@code <timestamp>|<rcid>} — the same timestamp-plus-tie-break shape the merge
 * arrived at independently.
 *
 * <pre>
 * centauri.endpoint.wikipedia-de.protocol = wikipedia
 * centauri.endpoint.wikipedia-de.baseUrl  = https://de.wikipedia.org
 * centauri.endpoint.wikipedia-de.language = de       (optional, else from host)
 * </pre>
 *
 * <p>Wikimedia requires a descriptive {@code User-Agent} and blocks generic
 * ones, which is why {@link WikipediaFeedInstance} sends one naming this
 * project.
 */
@Component
public class WikipediaFeedProtocol implements FeedProtocol {

    public static final String ID = "wikipedia";

    static final String API_PATH = "/w/api.php";

    static final String EXTRA_LANGUAGE = "language";

    private final CentauriHttpClient http;
    private final ObjectMapper mapper;

    @Autowired
    public WikipediaFeedProtocol(ObjectMapper mapper) {
        this(new CentauriHttpClient.JdkCentauriHttpClient(), mapper);
    }

    WikipediaFeedProtocol(CentauriHttpClient http, ObjectMapper mapper) {
        this.http = http;
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "MediaWiki recent changes";
    }

    @Override
    public FeedSourceInstance instantiate(FeedInstanceConfig cfg) {
        if (cfg.baseUrl().isBlank()) {
            throw new IllegalArgumentException("endpoint '" + cfg.instanceId()
                    + "' needs a baseUrl, e.g. https://de.wikipedia.org");
        }
        return new WikipediaFeedInstance(cfg, http, mapper);
    }
}
