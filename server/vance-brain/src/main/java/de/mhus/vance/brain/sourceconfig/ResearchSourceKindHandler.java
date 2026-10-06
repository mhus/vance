package de.mhus.vance.brain.sourceconfig;

import de.mhus.vance.shared.document.kind.KindHandler;
import de.mhus.vance.shared.document.kind.validate.Finding;
import de.mhus.vance.shared.document.kind.validate.KindValidationContext;
import de.mhus.vance.toolpack.research.SearchProtocol;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * {@link KindHandler} for the {@code vance-research-source} kind — one search
 * source of the Zarniwoop dispatcher ({@code _vance/config/research/<id>.yaml},
 * spec {@code specification/public/zarniwoop-service.md} §8).
 *
 * <p><b>Kind and location are independent</b>, same rule as the scheduler: a
 * document carrying this kind is a source definition wherever it lives and
 * gets the same validation; only one under {@code _vance/config/research/} is
 * also <em>live</em>, because the dispatcher reads exactly that folder.
 *
 * <p>The findings mean exactly what the dispatcher would silently do to the
 * source ({@code SearchProviderFactory}): drop it. A missing or unknown
 * {@code protocol} leaves an endpoint that never answers a search and never
 * says why — here it says so while the document is being edited. Parsing
 * delegates to the canonical {@link SourceConfigLoader#parse}, so a finding
 * means what every other reader of these documents sees.
 *
 * <p><b>{@link #detects} is deliberately not implemented.</b> The body shape
 * ({@code protocol}/{@code baseUrl}/{@code apiKey}/{@code enabled}) is the
 * shared {@code SourceConfig} record — feed sources and Jaglan mounts look
 * exactly the same. A detector here would claim documents belonging to two
 * other subsystems, and the SPI's own rule is to claim narrowly. The marker
 * {@code $meta.kind} is what types these documents; templates, the setup
 * wizard and the form view all write it.
 *
 * <p><b>{@link #detectsPath} is implemented</b> — location, not body, is the
 * marker for this kind. The folder {@code _vance/config/research/} is exclusive
 * to this subsystem, so the claim is narrow: every document there is a source
 * definition, and an untyped {@code doc_write} into the config tree lands as
 * this kind instead of {@code text} (which would silently skip validation).
 * Kind and location stay independent in the other direction — a source
 * document elsewhere keeps its kind, exactly what the rule above says.
 */
@Service
public class ResearchSourceKindHandler implements KindHandler {

    public static final String KIND = "vance-research-source";

    private final SourceConfigLoader loader;
    private final Set<String> protocolIds;

    public ResearchSourceKindHandler(SourceConfigLoader loader, List<SearchProtocol> protocols) {
        this.loader = loader;
        this.protocolIds = protocols.stream().map(SearchProtocol::id).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public String getName() {
        return KIND;
    }

    /**
     * The config tree is the marker: every document under
     * {@code _vance/config/research/} is a source definition. This is a claim
     * on <em>location</em>, not on body shape — see the class javadoc for why
     * {@link #detects(String)} stays unimplemented. It types the untyped
     * {@code doc_write} into the config tree as this kind instead of
     * {@code text}, so the validator above actually runs on what lands there.
     */
    @Override
    public boolean detectsPath(String documentPath) {
        return documentPath.startsWith(SourceConfigPaths.RESEARCH);
    }

    @Override
    public List<Finding> validate(String content, KindValidationContext ctx) {
        String target = StringUtils.isBlank(ctx.docPath()) ? KIND : ctx.docPath();
        SourceConfig config;
        try {
            config = loader.parse(SourceConfigKindRules.sourceName(ctx.docPath()), target, content);
        } catch (SourceConfigParseException e) {
            return List.of(Finding.error(target, "research-source-parse", e.getMessage()));
        }

        List<Finding> findings = new ArrayList<>();
        String protocol = config.protocol();
        if (StringUtils.isBlank(protocol)) {
            findings.add(Finding.error(
                    target, "research-source-protocol", "no protocol set — the dispatcher skips this source"));
        } else if (!protocolIds.contains(protocol)) {
            findings.add(Finding.error(
                    target,
                    "research-source-protocol-unknown",
                    "unknown protocol '" + protocol + "' — this deployment serves: " + new TreeSet<>(protocolIds)));
        }
        if (StringUtils.isBlank(config.baseUrl())) {
            findings.add(Finding.warning(
                    target,
                    "research-source-base-url",
                    "no baseUrl set — most protocols refuse to instantiate without one"));
        }
        findings.addAll(SourceConfigKindRules.credentialFindings(
                target, "research-source-credential-undeclared", config.apiKey()));
        return findings;
    }
}
