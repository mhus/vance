package de.mhus.vance.brain.tools;

import de.mhus.vance.brain.servertool.ServerToolService;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Discovers every {@link Tool} Spring bean at startup and feeds them
 * to {@link ServerToolService} as the bottom layer of the tool
 * cascade. Bean scanning is the only deployment path for built-in
 * server tools.
 *
 * <p>This class is intentionally <b>not</b> a {@link ToolSource} — the
 * configured cascade ({@code project → _vance → built-in}) is owned by
 * {@code ServerToolService} and surfaced to the dispatcher through
 * {@code ConfiguredToolSource}. Exposing built-ins as a separate
 * dispatcher source would bypass the cascade's disable semantics
 * ({@code ServerToolDocument#enabled=false} in {@code _vance}/project
 * has to be able to suppress a built-in tool).
 *
 * <p>Duplicate tool names across beans fail fast at construction.
 */
@Component
@Slf4j
public class BuiltInToolSource implements ServerToolService.BuiltInProvider {

    private final Map<String, Tool> byName;
    private final List<Tool> tools;
    private final ServerToolService serverToolService;

    public BuiltInToolSource(List<Tool> beans, ServerToolService serverToolService) {
        Map<String, List<Tool>> grouped = beans.stream()
                .collect(Collectors.groupingBy(Tool::name));
        grouped.forEach((name, dupes) -> {
            if (dupes.size() > 1) {
                throw new IllegalStateException(
                        "Duplicate server tool name '" + name + "': "
                                + dupes.stream().map(t -> t.getClass().getName()).toList());
            }
        });
        this.tools = List.copyOf(beans);
        this.byName = beans.stream().collect(Collectors.toUnmodifiableMap(Tool::name, t -> t));
        this.serverToolService = serverToolService;
        log.info("BuiltInToolSource registered {} tools: {}",
                tools.size(), tools.stream().map(Tool::name).toList());
    }

    @PostConstruct
    void register() {
        serverToolService.setBuiltInProvider(this);
    }

    @Override
    public List<Tool> list() {
        return tools;
    }

    @Override
    public Optional<Tool> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }
}
