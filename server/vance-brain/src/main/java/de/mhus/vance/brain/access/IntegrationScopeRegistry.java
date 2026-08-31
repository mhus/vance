package de.mhus.vance.brain.access;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * All {@link IntegrationScopeProfile} beans on the classpath, by id.
 *
 * <p><b>A duplicate id fails the boot.</b> Two profiles under one name would
 * make the {@code scp} claim ambiguous, and the losing one would be whichever
 * order Spring happened to hand them over — a token would grant a different
 * surface set depending on bean ordering. Same stance as the duplicate
 * {@code ShareHandler} id and the mandatory single permission provider: an
 * ambiguity in an authorization input is a startup failure, not a warning.
 *
 * <p>An empty registry is fine — it just means no integration token can be
 * minted, which is the correct state for a brain with no addon that wants one.
 */
@Component
@Slf4j
public class IntegrationScopeRegistry {

    private final Map<String, IntegrationScopeProfile> byId;

    public IntegrationScopeRegistry(List<IntegrationScopeProfile> profiles) {
        Map<String, IntegrationScopeProfile> map = new LinkedHashMap<>();
        for (IntegrationScopeProfile profile : profiles) {
            String id = profile.id();
            if (id == null || id.isBlank()) {
                throw new IllegalStateException(
                        "IntegrationScopeProfile " + profile.getClass().getName()
                                + " has a blank id");
            }
            if (profile.surfaces().isEmpty()) {
                // A profile that opens nothing can only produce tokens that are
                // rejected on every request. Better to say so at boot.
                throw new IllegalStateException(
                        "IntegrationScopeProfile '" + id + "' ("
                                + profile.getClass().getName() + ") declares no surfaces");
            }
            IntegrationScopeProfile clash = map.put(id, profile);
            if (clash != null) {
                throw new IllegalStateException(
                        "Duplicate IntegrationScopeProfile id '" + id + "': "
                                + clash.getClass().getName() + " and "
                                + profile.getClass().getName());
            }
        }
        this.byId = Map.copyOf(map);
        log.info("IntegrationScopeRegistry: {} profile(s) {}", byId.size(), byId.keySet());
    }

    public Optional<IntegrationScopeProfile> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** All profiles, for the mint form. */
    public List<IntegrationScopeProfile> all() {
        return List.copyOf(byId.values());
    }
}
