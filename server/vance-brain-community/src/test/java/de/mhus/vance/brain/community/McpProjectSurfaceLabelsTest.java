package de.mhus.vance.brain.community;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.brain.mcpserver.McpProjectCapability;
import de.mhus.vance.brain.mcpserver.McpProjectToolCatalog;
import de.mhus.vance.toolpack.Tool;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The project-scoped MCP door lists its tools explicitly, per capability — a door to external agents
 * must not widen because somebody gave a new tool a label. This test keeps that explicit list and the
 * family labels honest in both directions where it can:
 *
 * <ul>
 *   <li>every tool the surface lists exists and carries the label of its family (a rename or a
 *       mislabel shows here, not as a missing tool in production);
 *   <li>for a family the door offers <b>whole</b> ({@code workflow}, {@code file}, {@code exec}), every built-in tool carrying the
 *       family label is either on the surface or named in {@link #NOT_ON_THE_DOOR} with a reason. A new
 *       {@code workflow_*} tool therefore forces a decision about the door instead of silently being
 *       left off — or, worse, assumed on.
 * </ul>
 *
 * The {@code document} label is deliberately not checked in reverse: it covers dozens of tools the door
 * leaves out on purpose ({@code doc_purge}, {@code doc_lock_*}, …).
 */
class McpProjectSurfaceLabelsTest {

    /** Built-in tools carrying a whole-family label that the door deliberately does not offer. */
    private static final Map<McpProjectCapability, Set<String>> NOT_ON_THE_DOOR = Map.of(
            McpProjectCapability.WORKFLOW,
            Set.of(),
            // A hard delete in a workspace — the document door's delete is a soft one into the trash,
            // this has no trash.
            McpProjectCapability.FILE,
            Set.of("work_file_delete"),
            // Running code is what the exec capability and its rules are for; as a file tool it would be a
            // way to run code under a capability that does not say so.
            McpProjectCapability.EXEC,
            Set.of("execute_work_javascript"));

    private static final Set<McpProjectCapability> OFFERED_WHOLE =
            Set.of(McpProjectCapability.WORKFLOW, McpProjectCapability.FILE, McpProjectCapability.EXEC);

    @Test
    void everySurfaceToolExistsAndCarriesItsFamilyLabel() throws ClassNotFoundException {
        Map<String, Tool> byName =
                BuiltInTools.all().stream().collect(Collectors.toMap(Tool::name, t -> t, (a, b) -> a));

        Set<String> violations = new TreeSet<>();
        for (McpProjectCapability capability : McpProjectCapability.values()) {
            for (String name : McpProjectToolCatalog.surfaceToolNames(capability)) {
                Tool tool = byName.get(name);
                if (tool == null) {
                    violations.add(capability.wire() + ": " + name + " is not a built-in tool");
                } else if (!tool.labels().contains(capability.familyLabel())) {
                    violations.add(capability.wire() + ": " + name + " lacks label '" + capability.familyLabel()
                            + "' (has " + new TreeSet<>(tool.labels()) + ")");
                }
            }
        }
        assertThat(violations)
                .as("surface tools that do not match their family")
                .isEmpty();
    }

    @Test
    void aFamilyOfferedWhole_hasNoLabelledToolLeftOff() throws ClassNotFoundException {
        List<Tool> tools = BuiltInTools.all();

        Set<String> violations = new TreeSet<>();
        for (McpProjectCapability capability : OFFERED_WHOLE) {
            Set<String> listed = Set.copyOf(McpProjectToolCatalog.surfaceToolNames(capability));
            Set<String> exempt = NOT_ON_THE_DOOR.getOrDefault(capability, Set.of());
            for (Tool tool : tools) {
                if (tool.labels().contains(capability.familyLabel())
                        && !listed.contains(tool.name())
                        && !exempt.contains(tool.name())) {
                    violations.add(capability.wire() + ": " + tool.name()
                            + " carries the family label but is neither on the surface nor in NOT_ON_THE_DOOR");
                }
            }
        }
        assertThat(violations)
                .as("decide for each: add to McpProjectToolCatalog or NOT_ON_THE_DOOR")
                .isEmpty();
    }
}
