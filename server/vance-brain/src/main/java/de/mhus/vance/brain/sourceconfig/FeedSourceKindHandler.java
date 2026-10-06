package de.mhus.vance.brain.sourceconfig;

import de.mhus.vance.shared.document.kind.KindHandler;
import de.mhus.vance.shared.document.kind.validate.Finding;
import de.mhus.vance.shared.document.kind.validate.KindValidationContext;
import de.mhus.vance.toolpack.feed.FeedProtocol;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * {@link KindHandler} for the {@code vance-feed-source} kind — one feed
 * endpoint of the Centauri feed reader ({@code _vance/config/feeds/<id>.yaml},
 * spec {@code specification/public/centauri-service.md}).
 *
 * <p>Same kind-and-location contract as the research source: a document
 * carrying this kind is a feed source definition wherever it lives and gets
 * the same validation; only one under {@code _vance/config/feeds/} is also
 * <em>live</em>, because {@code CentauriSourceFactory} reads exactly that
 * folder. {@code detects} stays unimplemented for the same reason — the body
 * shape is the shared {@code SourceConfig} record, so a body detector would
 * claim the other two subsystems' documents.
 *
 * <p>The findings mean what the factory does to the source
 * ({@code CentauriSourceFactory} drops it): a missing or unknown
 * {@code protocol} is an endpoint that never answers a feed and never says
 * why — here it says so while the document is being edited. The protocol set
 * is the deployed {@link FeedProtocol} beans, which includes the examples the
 * centauri addon ships, so an installation without them validates honestly.
 */
@Service
public class FeedSourceKindHandler implements KindHandler {

    public static final String KIND = "vance-feed-source";

    private final SourceConfigLoader loader;
    private final Set<String> protocolIds;

    public FeedSourceKindHandler(SourceConfigLoader loader, List<FeedProtocol> protocols) {
        this.loader = loader;
        this.protocolIds = protocols.stream().map(FeedProtocol::id).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public String getName() {
        return KIND;
    }

    /**
     * The config tree is the marker: every document under
     * {@code _vance/config/feeds/} is a feed source definition — see the class
     * javadoc for why {@code detects(String)} stays unimplemented.
     */
    @Override
    public boolean detectsPath(String documentPath) {
        return documentPath.startsWith(SourceConfigPaths.FEEDS);
    }

    @Override
    public List<Finding> validate(String content, KindValidationContext ctx) {
        String target = StringUtils.isBlank(ctx.docPath()) ? KIND : ctx.docPath();
        SourceConfig config;
        try {
            config = loader.parse(SourceConfigKindRules.sourceName(ctx.docPath()), target, content);
        } catch (SourceConfigParseException e) {
            return List.of(Finding.error(target, "feed-source-parse", e.getMessage()));
        }

        List<Finding> findings = new ArrayList<>();
        String protocol = config.protocol();
        if (StringUtils.isBlank(protocol)) {
            findings.add(Finding.error(
                    target, "feed-source-protocol", "no protocol set — the feed factory skips this source"));
        } else if (!protocolIds.contains(protocol)) {
            findings.add(Finding.error(
                    target,
                    "feed-source-protocol-unknown",
                    "unknown protocol '" + protocol + "' — this deployment serves: " + new TreeSet<>(protocolIds)));
        }
        if (StringUtils.isBlank(config.baseUrl())) {
            findings.add(Finding.warning(
                    target,
                    "feed-source-base-url",
                    "no baseUrl set — most feed protocols refuse to instantiate without one"));
        }
        findings.addAll(
                SourceConfigKindRules.credentialFindings(target, "feed-source-credential-undeclared", config.apiKey()));
        return findings;
    }
}
