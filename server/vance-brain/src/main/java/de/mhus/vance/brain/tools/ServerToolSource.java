package de.mhus.vance.brain.tools;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Discovers every {@link Tool} Spring bean at startup and exposes them
 * as a single source. Bean scanning is the only deployment path for
 * built-in server tools — meta-tools and domain tools both arrive here.
 *
 * <p>Duplicate tool names across beans fail fast at construction, since
 * a duplicate name makes dispatch ambiguous and is virtually always a
 * wiring mistake.
 */
@Component
@Slf4j
public class ServerToolSource implements ToolSource {

    public static final String SOURCE_ID = "server";

    private final List<Tool> tools;

    public ServerToolSource(List<Tool> beans) {
        beans.stream()
                .collect(java.util.stream.Collectors.groupingBy(Tool::name))
                .forEach((name, dupes) -> {
                    if (dupes.size() > 1) {
                        throw new IllegalStateException(
                                "Duplicate server tool name '" + name + "': "
                                        + dupes.stream().map(t -> t.getClass().getName()).toList());
                    }
                });
        this.tools = List.copyOf(beans);
        log.info("ServerToolSource registered {} tools: {}",
                tools.size(), tools.stream().map(Tool::name).toList());
    }

    @Override
    public String sourceId() {
        return SOURCE_ID;
    }

    @Override
    public List<Tool> tools(ToolInvocationContext ctx) {
        return tools;
    }
}
