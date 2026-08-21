package de.mhus.vance.brain.kit;

import de.mhus.vance.api.kit.KitInheritDto;
import de.mhus.vance.api.kit.KitSignatureStatus;
import de.mhus.vance.api.kit.KitSourceDto;
import de.mhus.vance.api.kit.KitSourceType;
import de.mhus.vance.shared.kit.KitException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Picks the loader for a kit reference and runs it.
 *
 * <p>The single place that turns "a url plus a tenant" into "a directory
 * on disk": resolve which source covers the url, find the loader for its
 * type, load. Everything after this point works on the directory.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KitSourceLoaders {

    private final KitSourceRegistry sources;
    private final KitSignatureGate signatureGate;
    private final KitLicenseGate licenseGate;
    private final List<KitSourceLoader> loaders;

    /**
     * A loaded kit together with where it came from.
     *
     * @param kit the materialised tree and its descriptor
     * @param config the source that covered it
     * @param signature what the signature check concluded — recorded so
     *        the install can say what was true at the time
     */
    public record LoadResult(
            KitRepoLoader.LoadedKit kit,
            KitSourceDto config,
            KitSignatureStatus signature) {}

    /** Convenience for callers that only need the tree, e.g. inherit layers. */
    public KitRepoLoader.LoadedKit load(
            KitAccess access, KitInheritDto source, Path target) {
        return loadFrom(access, source, target).kit();
    }

    /**
     * Load {@code source} for {@code access} into {@code target}.
     */
    public LoadResult loadFrom(KitAccess access, KitInheritDto source, Path target) {
        if (source == null || source.getUrl() == null || source.getUrl().isBlank()) {
            throw new KitException("kit source url must not be blank");
        }
        KitSourceDto config = sources.resolve(access.tenantId(), source.getUrl());
        log.debug("KitSourceLoaders: {} resolves to source '{}' (type={}, signature={})",
                source.getUrl(), config.getId(), config.getType(), config.getSignature());
        KitRepoLoader.LoadedKit loaded = loaderFor(config.getType()).load(
                source, config, access, target);
        // A `render:` list only means something for a source that assembles
        // per installation. Saying so beats doing nothing quietly: the same
        // kit is often both served by a host and checked into git, and from
        // the checkout the placeholders stay literal.
        if (config.getType() != KitSourceType.ODE
                && loaded.descriptor().getRender() != null
                && !loaded.descriptor().getRender().isEmpty()) {
            log.warn("Kit '{}' from {} source '{}' declares render: — ignored."
                            + " Templates are only applied to ODE sources, so these files"
                            + " keep their placeholders: {}",
                    loaded.descriptor().getName(), config.getType(), config.getId(),
                    loaded.descriptor().getRender());
        }
        // Right here, and not in the installer: this is the one point where
        // the loaded tree and the source it came from are both in hand. Every
        // inherit passes through again with its own source's policy.
        KitSignatureStatus signature =
                signatureGate.enforce(loaded.root(), loaded.descriptor(), config);
        // Genuine and permitted are separate questions, asked in that order:
        // there is no point checking who a kit belongs to before knowing
        // whether the answer can be trusted.
        licenseGate.enforce(loaded.descriptor(), signature, access.storeAccount(),
                config, Instant.now());
        return new LoadResult(loaded, config, signature);
    }

    private KitSourceLoader loaderFor(KitSourceType type) {
        for (KitSourceLoader loader : loaders) {
            if (loader.supports(type)) return loader;
        }
        // Reachable today for LIBRARY: the type is configurable before the
        // loader exists, deliberately — a tenant can see and override the
        // default entry from the start. Saying so plainly beats a
        // NullPointerException three frames down.
        throw new KitException("no loader for kit source type " + type
                + " — this Vancetope build cannot load kits of that kind");
    }
}
