package de.mhus.vance.brain.centauri.protocols;

import de.mhus.vance.toolpack.feed.FeedInstanceConfig;
import de.mhus.vance.toolpack.feed.FeedProtocol;
import de.mhus.vance.toolpack.feed.FeedSourceInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Earthquakes from the USGS event service — the second protocol, and the one
 * that proves the SPI is not shaped like the ode contract.
 *
 * <p>It is also the cheapest realistic source there is: no account, no key, no
 * per-instance credential, and entries that are genuinely readable (a title, a
 * place, a link) rather than a firehose of edits.
 *
 * <p>Two contract details it exercises that nothing else does:
 * <ul>
 *   <li>A <b>time cursor</b>. The paging bound is a timestamp, not an id, which
 *       is exactly why {@code cursorAfter} is overridable.
 *   <li><b>Entries without a language.</b> USGS has no language field, so this
 *       source is the live test of the rule that an undeclared language passes a
 *       language filter — with the opposite rule the stream would be
 *       permanently empty for anyone who set one.
 * </ul>
 *
 * <p>Note which endpoint this uses. The {@code /feed/v1.0/summary/*.geojson}
 * files are fixed-window snapshots with no paging at all; the query service
 * below has {@code orderby}, {@code limit} and time bounds, which is what an
 * endless scroll needs.
 *
 * <pre>
 * centauri.endpoint.usgs.protocol = usgs
 * centauri.endpoint.usgs.baseUrl  = https://earthquake.usgs.gov
 * </pre>
 */
@Component
public class UsgsFeedProtocol implements FeedProtocol {

    public static final String ID = "usgs";

    static final String DEFAULT_BASE_URL = "https://earthquake.usgs.gov";

    static final String QUERY_PATH = "/fdsnws/event/1/query";

    private final CentauriHttpClient http;
    private final ObjectMapper mapper;

    @Autowired
    public UsgsFeedProtocol(ObjectMapper mapper) {
        this(new CentauriHttpClient.JdkCentauriHttpClient(), mapper);
    }

    UsgsFeedProtocol(CentauriHttpClient http, ObjectMapper mapper) {
        this.http = http;
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "USGS earthquakes";
    }

    @Override
    public FeedSourceInstance instantiate(FeedInstanceConfig cfg) {
        return new UsgsFeedInstance(cfg, http, mapper);
    }
}
