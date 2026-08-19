package de.mhus.vance.brain.zarniwoop.protocols;

import de.mhus.vance.brain.zarniwoop.ZarniwoopContentStore;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.toolpack.research.ProviderInstanceConfig;
import de.mhus.vance.toolpack.research.SearchModality;
import de.mhus.vance.toolpack.research.SearchProtocol;
import de.mhus.vance.toolpack.research.SearchProviderInstance;
import de.mhus.vance.toolpack.research.SearchTier;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Search adapter for foreign applications that embed {@code vance-ode-zarniwoop}.
 *
 * <p>This is the protocol that lets a company archive, a news index or a
 * domain catalogue become a research provider <b>without a pull request in this
 * repository</b>. Vancetope knows the contract; it does not know the source.
 *
 * <p><b>Built in rather than an add-on</b>, on the same rule Centauri follows:
 * what ships in the brain is <i>the contract</i>. Example sources belong in
 * add-ons — {@code ode} is not an example source, it is the door.
 *
 * <p>The protocol id is {@code ode}, deliberately the same word Centauri uses
 * for its own Ode protocol. Two registers, one vocabulary, no collision: an
 * operator writes {@code research.endpoint.<id>.protocol = ode} next to
 * {@code centauri.endpoint.<id>.protocol = ode} and means the same kind of thing
 * both times.
 *
 * <p><b>What can be searched comes from the far end.</b> Everything interesting
 * about this protocol is on the instance ({@link OdeSearchInstance}), which
 * fetches {@code /capabilities} and reports what that service says it can serve.
 * The declarations on this bean are near-meaningless by comparison — see
 * {@link #modalitiesSupported()}.
 */
@Component
@Slf4j
public class OdeSearchProtocol implements SearchProtocol {

    public static final String ID = "ode";

    private final SettingService settings;
    private final ObjectMapper objectMapper;
    private final ZarniwoopContentStore contentStore;
    private final OdeSearchHttp http;

    @Autowired
    public OdeSearchProtocol(
            SettingService settings,
            ObjectMapper objectMapper,
            ZarniwoopContentStore contentStore) {
        this(settings, objectMapper, contentStore, new OdeSearchHttp.JdkOdeSearchHttp());
    }

    /** Test-seam constructor. */
    OdeSearchProtocol(
            SettingService settings,
            ObjectMapper objectMapper,
            ZarniwoopContentStore contentStore,
            OdeSearchHttp http) {
        this.settings = settings;
        this.objectMapper = objectMapper;
        this.contentStore = contentStore;
        this.http = http;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Ode (foreign application search)";
    }

    /**
     * Every modality, because this bean cannot know better and nothing load-
     * bearing reads it.
     *
     * <p>Worth being blunt about, so the next reader does not go looking for a
     * meaning that is not there: {@code SearchProtocol.modalitiesSupported()} is
     * consumed by tests only. The dispatcher filters on
     * {@link SearchProviderInstance#modalities()}, which for this protocol comes
     * from the remote {@code /capabilities} call. Declaring the union here is
     * therefore both harmless and uninformative — the honest value for "it
     * depends entirely on the endpoint".
     */
    @Override
    public Set<SearchModality> modalitiesSupported() {
        return Set.of(SearchModality.values());
    }

    /**
     * Both tiers, for the same reason. Whether a given endpoint accepts
     * {@link SearchTier#EXPERT} is its own declaration; a source that cannot act
     * on expert params says so in its capabilities and never receives any.
     */
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
                    "OdeSearchProtocol cannot instantiate config with protocol '"
                            + cfg.protocolId() + "'");
        }
        if (cfg.baseUrl() == null || cfg.baseUrl().isBlank()) {
            // Refusing here rather than building a dead instance: unlike a
            // missing credential (which the endpoint may not need at all), there
            // is nothing an Ode endpoint without a base URL could ever do, and
            // the factory logs the refusal against the endpoint id.
            throw new IllegalArgumentException(
                    "Ode endpoint '" + cfg.instanceId() + "' has no baseUrl");
        }
        return new OdeSearchInstance(cfg, settings, objectMapper, contentStore, http);
    }

    /**
     * HTTP test-seam. Same shape as the sibling protocols' seams, with two
     * differences this contract needs: a POST (search carries a structured body)
     * and a byte-returning GET (a hit body may be a PDF).
     */
    interface OdeSearchHttp {

        record Response(int statusCode, String body) { }

        record BinaryResponse(int statusCode, byte[] body, @Nullable String contentType) { }

        Response get(URI url, @Nullable String bearer, Duration timeout) throws Exception;

        Response post(URI url, @Nullable String bearer, String json, Duration timeout)
                throws Exception;

        BinaryResponse getBytes(URI url, @Nullable String bearer, Duration timeout)
                throws Exception;

        final class JdkOdeSearchHttp implements OdeSearchHttp {

            private static final String USER_AGENT =
                    "Vance-Zarniwoop/0.1 (+https://github.com/mhus/vance)";

            private final HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

            @Override
            public Response get(URI url, @Nullable String bearer, Duration timeout)
                    throws Exception {
                HttpResponse<String> r = client.send(
                        builder(url, bearer, timeout).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                return new Response(r.statusCode(), r.body() == null ? "" : r.body());
            }

            @Override
            public Response post(URI url, @Nullable String bearer, String json, Duration timeout)
                    throws Exception {
                HttpResponse<String> r = client.send(
                        builder(url, bearer, timeout)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(json))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                return new Response(r.statusCode(), r.body() == null ? "" : r.body());
            }

            @Override
            public BinaryResponse getBytes(URI url, @Nullable String bearer, Duration timeout)
                    throws Exception {
                HttpResponse<byte[]> r = client.send(
                        builder(url, bearer, timeout).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                return new BinaryResponse(
                        r.statusCode(),
                        r.body() == null ? new byte[0] : r.body(),
                        r.headers().firstValue("content-type").orElse(null));
            }

            private HttpRequest.Builder builder(
                    URI url, @Nullable String bearer, Duration timeout) {
                HttpRequest.Builder b = HttpRequest.newBuilder()
                        .uri(url)
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "application/json")
                        .timeout(timeout);
                if (bearer != null && !bearer.isBlank()) {
                    b = b.header("Authorization", "Bearer " + bearer);
                }
                return b;
            }
        }
    }
}
