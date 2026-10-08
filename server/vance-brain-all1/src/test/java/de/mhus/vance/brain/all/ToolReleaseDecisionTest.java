package de.mhus.vance.brain.all;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolLabels;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.stereotype.Component;

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
 * <p>The bean is never constructed: a Mockito instance that calls the real
 * {@code labels()} reads the declared set without wiring the tool's
 * collaborators.
 */
class ToolReleaseDecisionTest {

    private static final Set<String> DECISIONS = Set.of(ToolLabels.WORKER, ToolLabels.OPERATOR, ToolLabels.INTERNAL);

    @Test
    void everyBuiltInTool_carriesExactlyOneReleaseDecision() throws ClassNotFoundException {
        List<Class<? extends Tool>> tools = builtInTools();
        // Guards the scan itself — an empty classpath would pass vacuously.
        assertThat(tools).hasSizeGreaterThan(300);

        Set<String> violations = new TreeSet<>();
        for (Class<? extends Tool> type : tools) {
            Tool tool = mock(type, withSettings().defaultAnswer(Answers.CALLS_REAL_METHODS));
            Set<String> labels = tool.labels();
            long decisions = labels == null
                    ? 0
                    : labels.stream().filter(DECISIONS::contains).count();
            if (decisions != 1) {
                violations.add(type.getName() + " labels=" + labels);
            }
        }
        assertThat(violations)
                .as("each tool needs exactly one of %s in labels() — see ToolLabels", DECISIONS)
                .isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static List<Class<? extends Tool>> builtInTools() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(Tool.class));
        List<Class<? extends Tool>> out = new ArrayList<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("de.mhus.vance")) {
            Class<?> type = Class.forName(bd.getBeanClassName());
            if (Modifier.isAbstract(type.getModifiers())) continue;
            if (!AnnotatedElementUtils.hasAnnotation(type, Component.class)) continue;
            out.add((Class<? extends Tool>) type);
        }
        return out;
    }
}
