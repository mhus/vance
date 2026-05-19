package de.mhus.vance.brain.oauth;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Lookup over every {@link OAuthProvider} bean. Duplicate
 * {@link OAuthProvider#typeId()} fails fast at startup — having two
 * beans claim the same type would make the routing non-deterministic.
 *
 * <p>Mirrors the {@code ToolFactoryRegistry} pattern used elsewhere in
 * the brain — the consistency is intentional, the two registries are
 * structurally identical from the consumer's point of view.
 */
@Component
@Slf4j
public class OAuthProviderRegistry {

    private final Map<String, OAuthProvider> byType;

    public OAuthProviderRegistry(List<OAuthProvider> providers) {
        this.byType = providers.stream()
                .collect(Collectors.toUnmodifiableMap(
                        OAuthProvider::typeId,
                        p -> p,
                        (a, b) -> {
                            throw new IllegalStateException(
                                    "Duplicate OAuthProvider typeId '" + a.typeId()
                                            + "': " + a.getClass().getName()
                                            + " vs " + b.getClass().getName());
                        }));
        log.info("OAuthProviderRegistry registered {} provider type(s): {}",
                byType.size(), byType.keySet());
    }

    public Optional<OAuthProvider> find(String typeId) {
        return Optional.ofNullable(byType.get(typeId));
    }

    public List<OAuthProvider> list() {
        return List.copyOf(byType.values());
    }
}
