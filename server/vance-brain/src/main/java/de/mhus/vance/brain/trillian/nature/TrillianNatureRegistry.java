package de.mhus.vance.brain.trillian.nature;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Discovers all {@link TrillianNature} beans at startup and indexes
 * them by {@link TrillianNature#id() id}. The Trillian engine
 * framework looks up a Nature per turn via
 * {@link #resolve(String)} using the value of
 * {@code engineParams.nature} on the calling process.
 *
 * <p>Falls back to {@link TrillianNatureVoid} when the requested id is
 * unknown — keeps a misconfigured recipe from killing the engine,
 * with a WARN in the log.
 *
 * <p>An <em>ill-formed</em> id is a different matter and fails the boot.
 * The id is not just a lookup key: it is spliced into the service-account
 * name ({@code _trillian-<nature>-<instance>}) and into three recipe names
 * ({@code trillian-<nature>}, {@code trillian-user-<nature>},
 * {@code trillian-worker-<nature>}). A dash inside it would make the
 * account name ambiguous to read back, and the ids {@code user} and
 * {@code worker} would produce a control recipe that collides with the
 * other two families. Such a Nature would resolve recipes pointing at
 * something else — worth refusing to start over.
 */
@Component
@Slf4j
public class TrillianNatureRegistry {

    /**
     * Lower-case alphanumerics only. No dash, because the account name
     * {@code _trillian-<nature>-<instance>} is split on it; no upper case
     * or punctuation, because the id also has to survive as part of a
     * user name and three document paths.
     */
    private static final Pattern VALID_ID = Pattern.compile("[a-z0-9]+");

    /** Would collide with the user-loop and worker recipe families. */
    private static final Set<String> RESERVED_IDS = Set.of("user", "worker");

    private final Map<String, TrillianNature> byId;
    private final TrillianNature fallback;

    public TrillianNatureRegistry(List<TrillianNature> natures) {
        this.byId = new HashMap<>();
        for (TrillianNature n : natures) {
            String id = n.id();
            requireUsableId(id, n);
            TrillianNature prev = byId.put(id, n);
            if (prev != null) {
                log.warn("TrillianNature id='{}' provided by multiple beans: '{}' and '{}'. "
                                + "Last one wins.",
                        id, prev.getClass().getSimpleName(),
                        n.getClass().getSimpleName());
            }
        }
        TrillianNature zero = byId.get(TrillianNatureVoid.ID);
        this.fallback = zero != null ? zero : natures.stream().findFirst().orElse(null);
        log.info("TrillianNatureRegistry initialised with {} nature(s): {}",
                byId.size(), byId.keySet());
    }

    /**
     * Rejects an id that cannot safely be spliced into account and recipe
     * names. Throws rather than skipping: a skipped Nature silently
     * degrades to Nature void at runtime, which looks like the Nature simply
     * not working.
     */
    private static void requireUsableId(@Nullable String id, TrillianNature nature) {
        String bean = nature.getClass().getSimpleName();
        if (id == null || id.isBlank()) {
            throw new IllegalStateException(
                    "TrillianNature '" + bean + "' has an empty id");
        }
        if (!VALID_ID.matcher(id).matches()) {
            throw new IllegalStateException(
                    "TrillianNature '" + bean + "' has id '" + id
                            + "' — only lower-case letters and digits are allowed, "
                            + "because the id becomes part of the service-account name "
                            + "_trillian-<nature>-<instance> and of the recipe names");
        }
        if (RESERVED_IDS.contains(id)) {
            throw new IllegalStateException(
                    "TrillianNature '" + bean + "' uses the reserved id '" + id
                            + "' — its control recipe 'trillian-" + id
                            + "' would collide with the trillian-user-* / "
                            + "trillian-worker-* recipe families");
        }
    }

    /**
     * Looks up the Nature by id. Returns the registered Nature or —
     * when {@code id} is unknown, null or blank — the fallback
     * (Nature void). Callers read the id out of {@code engineParams}, where
     * it may legitimately be absent. Never
     * returns {@code null} as long as at least one Nature bean
     * exists (which {@link TrillianNatureVoid} guarantees).
     */
    public TrillianNature resolve(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return fallback;
        }
        TrillianNature hit = byId.get(id);
        if (hit != null) {
            return hit;
        }
        log.warn("TrillianNature id='{}' not registered — falling back to '{}'",
                id, fallback == null ? "<none>" : fallback.id());
        return fallback;
    }

    /** Convenience: returns the default fallback Nature. */
    public TrillianNature getDefault() {
        return fallback;
    }
}
