package de.mhus.vance.brain.tools.types;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Lookup over all {@link ToolFactory} beans. Duplicate {@code typeId()}
 * fails fast at startup — having two factories claim the same type
 * would make {@code ServerToolService} non-deterministic.
 */
@Component
@Slf4j
public class ToolFactoryRegistry {

    private final Map<String, ToolFactory> byType;

    public ToolFactoryRegistry(List<ToolFactory> factories) {
        this.byType = factories.stream()
                .collect(Collectors.toUnmodifiableMap(
                        ToolFactory::typeId,
                        f -> f,
                        (a, b) -> {
                            throw new IllegalStateException(
                                    "Duplicate ToolFactory typeId '" + a.typeId()
                                            + "': " + a.getClass().getName()
                                            + " vs " + b.getClass().getName());
                        }));
        log.info("ToolFactoryRegistry registered {} types: {}",
                byType.size(), byType.keySet());
    }

    public Optional<ToolFactory> find(String typeId) {
        return Optional.ofNullable(byType.get(typeId));
    }

    public List<ToolFactory> list() {
        return List.copyOf(byType.values());
    }
}
