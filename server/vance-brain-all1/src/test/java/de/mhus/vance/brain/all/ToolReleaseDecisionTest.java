package de.mhus.vance.brain.all;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolLabels;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Every built-in tool states its release decision: exactly one of
 * {@link ToolLabels#WORKER}, {@link ToolLabels#OPERATOR} and
 * {@link ToolLabels#INTERNAL}. The worker engines take their pool by the
 * {@code worker} label, so a tool without the decision would silently stay
 * invisible to them — the failure that left {@code setting_get} out of
 * Frankie. Forcing the decision at authoring time is what makes the label
 * a declaration rather than a convention.
 *
 * <p>Lives in the dev bundle because it is the one module with brain and
 * every first-party addon on one classpath. Covers the {@code @Component}
 * tool beans; tools produced by a factory from operator configuration
 * (MCP/REST packs, SMTP/IMAP, scripted and doc-lookup tools) carry the
 * labels the operator configured and are out of scope.
 *
 * <p>Scan and instantiation: {@link BuiltInTools}.
 */
class ToolReleaseDecisionTest {

    private static final Set<String> DECISIONS = Set.of(ToolLabels.WORKER, ToolLabels.OPERATOR, ToolLabels.INTERNAL);

    @Test
    void everyBuiltInTool_carriesExactlyOneReleaseDecision() throws ClassNotFoundException {
        List<Tool> tools = BuiltInTools.all();
        // Guards the scan itself — an empty classpath would pass vacuously.
        assertThat(tools).hasSizeGreaterThan(300);

        Set<String> violations = new TreeSet<>();
        for (Tool tool : tools) {
            Set<String> labels = tool.labels();
            long decisions = labels == null
                    ? 0
                    : labels.stream().filter(DECISIONS::contains).count();
            if (decisions != 1) {
                violations.add(
                        org.mockito.Mockito.mockingDetails(tool)
                                        .getMockCreationSettings()
                                        .getTypeToMock()
                                        .getName() + " labels=" + labels);
            }
        }
        assertThat(violations)
                .as("each tool needs exactly one of %s in labels() — see ToolLabels", DECISIONS)
                .isEmpty();
    }
}
