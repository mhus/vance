package de.mhus.vance.brain.tools;

import de.mhus.vance.brain.servertool.ServerToolService;
import de.mhus.vance.toolpack.Tool;
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
 * configured cascade ({@code project → _tenant → built-in}) is owned by
 * {@code ServerToolService} and surfaced to the dispatcher through
 * {@code ConfiguredToolSource}. Exposing built-ins as a separate
 * dispatcher source would bypass the cascade's disable semantics
 * ({@code ServerToolDocument#enabled=false} in {@code _tenant}/project
 * has to be able to suppress a built-in tool).
 *
 * <p>Duplicate tool names across beans fail fast at construction.
 *
 * <p>A bean that requires a capability this pod lacks ({@link PodCapabilities} —
 * e.g. workspace access with {@code vance.workspace.mode=off}) is left out here,
 * so it is in no inventory and no caller can resolve it — unless a runner can provide
 * what it needs ({@link ProjectCapabilities#RUNNER_PROVIDED}): then it is offered to the
 * projects that have such a runner, and to no other.
 */
@Component
@Slf4j
public class BuiltInToolSource implements ServerToolService.BuiltInProvider {

    private final Map<String, Tool> byName;
    private final List<Tool> tools;
    /** Tools the pod cannot offer but a project's runner can — offered per project. */
    private final List<Tool> viaRunner;

    private final Map<String, Tool> viaRunnerByName;
    private final ServerToolService serverToolService;
    private final ProjectCapabilities projectCapabilities;

    public BuiltInToolSource(
            List<Tool> beans,
            ServerToolService serverToolService,
            PodCapabilities capabilities,
            ProjectCapabilities projectCapabilities) {
        Map<String, List<Tool>> grouped = beans.stream().collect(Collectors.groupingBy(Tool::name));
        grouped.forEach((name, dupes) -> {
            if (dupes.size() > 1) {
                throw new IllegalStateException("Duplicate server tool name '" + name + "': "
                        + dupes.stream().map(t -> t.getClass().getName()).toList());
            }
        });
        this.projectCapabilities = projectCapabilities;
        List<Tool> offered = beans.stream().filter(capabilities::supports).toList();
        this.viaRunner =
                beans.stream().filter(projectCapabilities::dependsOnRunner).toList();
        this.viaRunnerByName = viaRunner.stream().collect(Collectors.toUnmodifiableMap(Tool::name, t -> t));
        List<String> withheld = beans.stream()
                .filter(t -> !capabilities.supports(t))
                .filter(t -> !viaRunnerByName.containsKey(t.name()))
                .map(Tool::name)
                .toList();
        if (!withheld.isEmpty()) {
            log.info(
                    "BuiltInToolSource withholds {} tools this pod lacks a capability for: {}",
                    withheld.size(),
                    withheld);
        }
        if (!viaRunner.isEmpty()) {
            log.info(
                    "BuiltInToolSource offers {} tools only to projects whose runner provides them: {}",
                    viaRunner.size(),
                    viaRunner.stream().map(Tool::name).toList());
        }
        this.tools = offered;
        this.byName = offered.stream().collect(Collectors.toUnmodifiableMap(Tool::name, t -> t));
        this.serverToolService = serverToolService;
        log.info(
                "BuiltInToolSource registered {} tools: {}",
                tools.size(),
                tools.stream().map(Tool::name).toList());
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

    /** The pod's tools plus those the project's runner makes possible. */
    @Override
    public List<Tool> list(String tenantId, String projectId) {
        if (viaRunner.isEmpty()) {
            return tools;
        }
        List<Tool> visible = new java.util.ArrayList<>(tools);
        for (Tool tool : viaRunner) {
            if (projectCapabilities.supports(tool, tenantId, projectId)) {
                visible.add(tool);
            }
        }
        return visible;
    }

    @Override
    public Optional<Tool> find(String name, String tenantId, String projectId) {
        Tool pod = byName.get(name);
        if (pod != null) {
            return Optional.of(pod);
        }
        Tool tool = viaRunnerByName.get(name);
        return tool != null && projectCapabilities.supports(tool, tenantId, projectId)
                ? Optional.of(tool)
                : Optional.empty();
    }
}
