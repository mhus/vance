package de.mhus.vance.brain.kit;

import de.mhus.vance.api.kit.KitInheritDto;
import de.mhus.vance.api.kit.KitSourceDto;
import de.mhus.vance.api.kit.KitSourceType;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Materialises a kit reference into a local directory.
 *
 * <p>The point of the interface is that "where a kit comes from" and
 * "what is done with it once it is here" stop being the same question.
 * Everything downstream — inherit resolution, policy, records, prune —
 * works on a directory and does not care whether it was cloned, copied
 * or downloaded against an entitlement.
 *
 * <p>Implementations are Spring beans; {@link KitSourceLoaders} picks
 * one by {@link KitSourceType}.
 */
public interface KitSourceLoader {

    /** True when this loader handles the given source type. */
    boolean supports(KitSourceType type);

    /**
     * Populate {@code target} with the kit and parse its descriptor.
     *
     * @param source where the kit is, as referenced by the caller
     * @param config the resolved source configuration — type, signature
     *        policy, key. Carries what a url alone cannot say.
     * @param access who is fetching, and with what. Carries the
     *        credential plus the tenant and project — a source that
     *        assembles per project cannot be served from the url alone.
     * @param target an empty directory allocated by {@link KitWorkspace}
     */
    KitRepoLoader.LoadedKit load(
            KitInheritDto source,
            KitSourceDto config,
            KitAccess access,
            Path target);
}
