package de.mhus.vance.brain.kit.provisioning;

import de.mhus.vance.api.kit.KitProvisioningAuthority;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One entry of a project's kit desired-list: a kit that should be here,
 * as a provisioning mechanism reports it.
 *
 * <p>The list is derived per run and not persisted — it is what a
 * mechanism currently says, and storing it would create a second answer
 * to a question the source already answers. What does get stored is the
 * install record, and comparing the two is the whole of the check.
 *
 * @param sourceUrl url a kit reference resolves against — together with
 *        {@link #path} it is the kit's identity, the same pair the
 *        install record stores as its origin
 * @param path which kit at that url
 * @param revision what the source says its current content is, or null
 *        when it cannot say. Null means „do not check" rather than
 *        „changed": guessing would either refetch every tick or never.
 * @param authority carried over from the entry, so the decision about
 *        one kit does not have to look the entry up again
 * @param params what the entry asked the source for, carried along for
 *        the same reason and needed by the fetch.
 *
 *        <p>Load-bearing for the check: {@link #revision} comes from the
 *        source, which never saw these params (they go to the build call,
 *        not to the cacheable capabilities call). So a revision that
 *        stands still while the params changed still means „refetch" —
 *        and it is the local comparison that has to say so, not the
 *        source.
 */
public record DesiredKit(
        String sourceUrl,
        String path,
        @Nullable String revision,
        KitProvisioningAuthority authority,
        Map<String, Object> params) {

    public DesiredKit {
        params = params == null ? Map.of() : Map.copyOf(params);
        if (sourceUrl == null || sourceUrl.isBlank()) {
            throw new IllegalArgumentException("a desired kit needs a source url");
        }
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("a desired kit needs a path");
        }
        if (authority == null) {
            authority = KitProvisioningAuthority.defaultLevel();
        }
    }
}
