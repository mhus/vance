package de.mhus.vance.brain.trillian.nature;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Discovers all {@link TrillianNature} beans at startup and indexes
 * them by {@link TrillianNature#id() id}. The Trillian engine
 * framework looks up a Nature per turn via
 * {@link #resolve(String)} using the value of
 * {@code engineParams.nature} on the calling process.
 *
 * <p>Falls back to {@link TrillianNature0} when the requested id is
 * unknown — keeps a misconfigured recipe from killing the engine,
 * with a WARN in the log.
 */
@Component
@Slf4j
public class TrillianNatureRegistry {

    private final Map<String, TrillianNature> byId;
    private final TrillianNature fallback;

    public TrillianNatureRegistry(List<TrillianNature> natures) {
        this.byId = new HashMap<>();
        for (TrillianNature n : natures) {
            String id = n.id();
            if (id == null || id.isBlank()) {
                log.warn("TrillianNature '{}' has empty id — ignored",
                        n.getClass().getSimpleName());
                continue;
            }
            TrillianNature prev = byId.put(id, n);
            if (prev != null) {
                log.warn("TrillianNature id='{}' provided by multiple beans: '{}' and '{}'. "
                                + "Last one wins.",
                        id, prev.getClass().getSimpleName(),
                        n.getClass().getSimpleName());
            }
        }
        TrillianNature zero = byId.get(TrillianNature0.ID);
        this.fallback = zero != null ? zero : natures.stream().findFirst().orElse(null);
        log.info("TrillianNatureRegistry initialised with {} nature(s): {}",
                byId.size(), byId.keySet());
    }

    /**
     * Looks up the Nature by id. Returns the registered Nature or —
     * when {@code id} is unknown — the fallback (Nature-0). Never
     * returns {@code null} as long as at least one Nature bean
     * exists (which {@link TrillianNature0} guarantees).
     */
    public TrillianNature resolve(String id) {
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
