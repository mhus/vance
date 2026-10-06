package de.mhus.vance.brain.sourceconfig;

import de.mhus.vance.shared.document.kind.KindHandler;
import de.mhus.vance.shared.document.kind.validate.Finding;
import de.mhus.vance.shared.document.kind.validate.KindValidationContext;
import de.mhus.vance.toolpack.jaglan.JaglanProtocol;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * {@link KindHandler} for the {@code vance-mount-source} kind — one mounted
 * external tree of the Jaglan mount system
 * ({@code _vance/config/mounts/<id>.yaml}, spec
 * {@code specification/public/jaglan-system.md}).
 *
 * <p>Same kind-and-location contract as the research source: a document
 * carrying this kind is a mount definition wherever it lives and gets the
 * same validation; only one under {@code _vance/config/mounts/} is also
 * <em>live</em>, because {@code JaglanSourceFactory} reads exactly that
 * folder. {@code detects} stays unimplemented for the same reason — the body
 * shape is the shared {@code SourceConfig} record, so a body detector would
 * claim the other two subsystems' documents.
 *
 * <p>The findings mean what the factory does to the mount
 * ({@code JaglanSourceFactory} drops it): a missing or unknown
 * {@code protocol} is a mount that never appears in the tree and never says
 * why — here it says so while the document is being edited. The one
 * protocol-specific rule carries over verbatim: {@code local} refuses to
 * instantiate without a {@code rootDir}, so the validation says so at edit
 * time instead of at tree-open time.
 */
@Service
public class MountSourceKindHandler implements KindHandler {

    public static final String KIND = "vance-mount-source";

    /** The protocol that mounts a local directory. */
    static final String LOCAL_PROTOCOL = "local";

    /** Extra key the {@code local} protocol requires — see {@code LocalFileJaglanProtocol}. */
    static final String EXTRA_ROOT_DIR = "rootDir";

    private final SourceConfigLoader loader;
    private final Set<String> protocolIds;

    public MountSourceKindHandler(SourceConfigLoader loader, List<JaglanProtocol> protocols) {
        this.loader = loader;
        this.protocolIds = protocols.stream().map(JaglanProtocol::id).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public String getName() {
        return KIND;
    }

    /**
     * The config tree is the marker: every document under
     * {@code _vance/config/mounts/} is a mount definition — see the class
     * javadoc for why {@code detects(String)} stays unimplemented.
     */
    @Override
    public boolean detectsPath(String documentPath) {
        return documentPath.startsWith(SourceConfigPaths.MOUNTS);
    }

    @Override
    public List<Finding> validate(String content, KindValidationContext ctx) {
        String target = StringUtils.isBlank(ctx.docPath()) ? KIND : ctx.docPath();
        SourceConfig config;
        try {
            config = loader.parse(SourceConfigKindRules.sourceName(ctx.docPath()), target, content);
        } catch (SourceConfigParseException e) {
            return List.of(Finding.error(target, "mount-source-parse", e.getMessage()));
        }

        List<Finding> findings = new ArrayList<>();
        String protocol = config.protocol();
        if (StringUtils.isBlank(protocol)) {
            findings.add(Finding.error(
                    target, "mount-source-protocol", "no protocol set — the mount factory skips this source"));
        } else if (!protocolIds.contains(protocol)) {
            findings.add(Finding.error(
                    target,
                    "mount-source-protocol-unknown",
                    "unknown protocol '" + protocol + "' — this deployment serves: " + new TreeSet<>(protocolIds)));
        } else if (LOCAL_PROTOCOL.equals(protocol) && StringUtils.isBlank(config.extraString(EXTRA_ROOT_DIR, ""))) {
            findings.add(Finding.error(
                    target,
                    "mount-source-root-dir",
                    "protocol '" + LOCAL_PROTOCOL + "' needs a " + EXTRA_ROOT_DIR + " — "
                            + "the mount factory refuses to instantiate without one"));
        }
        if (StringUtils.isBlank(config.baseUrl()) && !LOCAL_PROTOCOL.equals(protocol)) {
            findings.add(Finding.warning(
                    target,
                    "mount-source-base-url",
                    "no baseUrl set — remote mount protocols refuse to instantiate without one"));
        }
        findings.addAll(SourceConfigKindRules.credentialFindings(
                target, "mount-source-credential-undeclared", config.apiKey()));
        return findings;
    }
}
