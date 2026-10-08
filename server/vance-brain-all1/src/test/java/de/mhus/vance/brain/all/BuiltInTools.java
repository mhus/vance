package de.mhus.vance.brain.all;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

import de.mhus.vance.toolpack.Tool;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.mockito.Answers;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.stereotype.Component;

/**
 * Every built-in tool bean on the dev-bundle classpath (brain plus all
 * first-party addons), as an instance whose declared metadata can be read.
 *
 * <p>The beans are never constructed: a Mockito instance that calls the real
 * methods returns the declared {@code name()} / {@code labels()} without
 * wiring the tool's collaborators. Tools produced by a factory from operator
 * configuration (MCP/REST packs, SMTP/IMAP, scripted, doc-lookup) are not
 * beans and therefore not part of this set.
 */
final class BuiltInTools {

    private BuiltInTools() {}

    static List<Tool> all() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(Tool.class));
        List<Tool> out = new ArrayList<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("de.mhus.vance")) {
            Class<?> type = Class.forName(bd.getBeanClassName());
            if (Modifier.isAbstract(type.getModifiers())) continue;
            if (!AnnotatedElementUtils.hasAnnotation(type, Component.class)) continue;
            out.add((Tool) mock(type, withSettings().defaultAnswer(Answers.CALLS_REAL_METHODS)));
        }
        return out;
    }
}
