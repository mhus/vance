package de.mhus.vance.brain.cluster;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.RegexPatternTypeFilter;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.util.ClassUtils;

/**
 * The eleven tick cadences of this package, pinned by name.
 *
 * <p>The general rule — no upper-case letter in any placeholder anywhere — is
 * enforced tree-wide by {@code ConfigPlaceholderNamingTest} in
 * {@code vance-shared}, which reads the sources and therefore also covers the
 * addons. This test is the narrower anchor underneath it: it names the keys
 * that the placement accelerator's debugging session turned up
 * ({@code planning/project-placement-labels.md} §4f), so a rename of one of
 * them is a deliberate act with a failing test attached rather than a quiet
 * edit. A generic rule cannot do that — it accepts any lower-case spelling,
 * including a renamed one.
 */
class SchedulingPlaceholderNamesTest {

    private static final String PACKAGE = "de.mhus.vance.brain.cluster";

    /**
     * A scanner that finds nothing would report perfect coverage, so the floor
     * is asserted separately. Deliberately well below the real count — this
     * guards against a broken scan, not against someone deleting a tick.
     */
    private static final int MINIMUM_EXPECTED_BEANS = 5;

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^:}]+)");

    @Test
    void theTickCadencesKeepTheirNames() {
        List<Class<?>> beans = scanBeans();
        // Extraction has to keep working for this to mean anything: a scanner
        // that finds no beans would report perfect coverage.
        assertThat(beans)
                .as("the scan itself has to work — an empty result is not coverage")
                .hasSizeGreaterThanOrEqualTo(MINIMUM_EXPECTED_BEANS);

        List<String> keys = new ArrayList<>();
        for (Class<?> bean : beans) {
            keys.addAll(placeholderKeys(bean));
        }

        assertThat(keys).contains(
                "vance.cluster.heartbeat-interval",
                "vance.cluster.master.distributor-interval",
                "vance.cluster.master.distributor-initial-delay",
                "vance.cluster.master.election-interval",
                "vance.cluster.master.election-initial-delay",
                "vance.cluster.cleanup.interval",
                "vance.cluster.cleanup.initial-delay",
                "vance.storage.orphan-sweep.interval",
                "vance.storage.orphan-sweep.initial-delay",
                "vance.session.stale-bind-sweep.interval",
                "vance.session.stale-bind-sweep.initial-delay");
    }

    private static Set<String> placeholderKeys(Class<?> bean) {
        Set<String> keys = new java.util.LinkedHashSet<>();
        for (Method method : bean.getDeclaredMethods()) {
            Scheduled scheduled = method.getAnnotation(Scheduled.class);
            if (scheduled != null) {
                collect(keys, scheduled.fixedDelayString());
                collect(keys, scheduled.fixedRateString());
                collect(keys, scheduled.initialDelayString());
                collect(keys, scheduled.cron());
            }
        }
        for (java.lang.reflect.Constructor<?> ctor : bean.getDeclaredConstructors()) {
            for (java.lang.reflect.Parameter parameter : ctor.getParameters()) {
                Value value = parameter.getAnnotation(Value.class);
                if (value != null) {
                    collect(keys, value.value());
                }
            }
        }
        for (java.lang.reflect.Field field : bean.getDeclaredFields()) {
            Value value = field.getAnnotation(Value.class);
            if (value != null) {
                collect(keys, value.value());
            }
        }
        return keys;
    }

    private static void collect(Set<String> keys, String expression) {
        Matcher matcher = PLACEHOLDER.matcher(expression);
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
    }

    private static List<Class<?>> scanBeans() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(true);
        // Conditional beans (ClusterMasterService and the ticks) are candidates
        // for this scan regardless of their @ConditionalOnProperty — the
        // scanner reads annotations, it does not evaluate conditions.
        scanner.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile(".*")));
        List<Class<?>> beans = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(PACKAGE)) {
            beans.add(ClassUtils.resolveClassName(
                    definition.getBeanClassName(), SchedulingPlaceholderNamesTest.class.getClassLoader()));
        }
        return beans;
    }
}
