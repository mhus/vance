package de.mhus.vance.brain.kit;

import de.mhus.vance.api.kit.KitInheritDto;
import de.mhus.vance.api.kit.KitSignatureStatus;
import de.mhus.vance.api.kit.KitSourceDto;
import de.mhus.vance.api.kit.KitSourceType;
import de.mhus.vance.shared.kit.KitException;
import java.nio.file.Path;
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
            String tenantId, KitInheritDto source, @Nullable String token, Path target) {
        return loadFrom(tenantId, source, token, target).kit();
    }

    /**
     * Load {@code source} for {@code tenantId} into {@code target}.
     *
     * @param tenantId whose source configuration applies — a url may be
     *        reachable for one tenant and unconfigured for another
     */
    public LoadResult loadFrom(
            String tenantId, KitInheritDto source, @Nullable String token, Path target) {
        if (source == null || source.getUrl() == null || source.getUrl().isBlank()) {
            throw new KitException("kit source url must not be blank");
        }
        KitSourceDto config = sources.resolve(tenantId, source.getUrl());
        log.debug("KitSourceLoaders: {} resolves to source '{}' (type={}, signature={})",
                source.getUrl(), config.getId(), config.getType(), config.getSignature());
        KitRepoLoader.LoadedKit loaded = loaderFor(config.getType()).load(
                source, config, token, target);
        // Right here, and not in the installer: this is the one point where
        // the loaded tree and the source it came from are both in hand. Every
        // inherit passes through again with its own source's policy.
        KitSignatureStatus signature =
                signatureGate.enforce(loaded.root(), loaded.descriptor(), config);
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
