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
 * Every {@code ${...}} placeholder in this package must be spelled in a form
 * Spring Boot's relaxed binding can recognise — lower-case with dashes.
 *
 * <p>This is not style. A placeholder is resolved by
 * {@code Environment.resolvePlaceholders}, which is <em>not</em> the binder:
 * Boot's relaxed lookup goes through {@code ConfigurationPropertyName.of},
 * and a name containing an upper-case letter is not a valid configuration
 * property name, so that source declines and resolution falls back to literal
 * matching against the raw sources. Consequence, measured before this test was
 * written: {@code ${vance.cluster.master.distributorInterval}} saw only a
 * literally camel-cased YAML key — an operator setting
 * {@code VANCE_CLUSTER_MASTER_DISTRIBUTOR_INTERVAL} got no error and no
 * effect, and the tick kept its default. The dashed spelling sees all three
 * (env, dashed YAML, camel-cased YAML), so it is strictly better.
 *
 * <p>Scoped to this package because that is where the finding was. The same
 * defect exists elsewhere in the tree and is listed in
 * {@code planning/project-placement-labels.md} §4e.
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
    void everyPlaceholderInThisPackage_isRelaxedBindingSafe() {
        List<Class<?>> beans = scanBeans();
        assertThat(beans)
                .as("the scan itself has to work — an empty result is not coverage")
                .hasSizeGreaterThanOrEqualTo(MINIMUM_EXPECTED_BEANS);

        List<String> offenders = new ArrayList<>();
        for (Class<?> bean : beans) {
            for (String key : placeholderKeys(bean)) {
                if (!key.equals(key.toLowerCase())) {
                    offenders.add(bean.getSimpleName() + ": ${" + key + "}");
                }
            }
        }

        assertThat(offenders)
                .as("an upper-case letter makes the key invisible to env vars and to "
                        + "dashed YAML — spell it lower-case with dashes")
                .isEmpty();
    }

    @Test
    void theTickCadencesAreActuallyCovered() {
        // Without this, the test above would still pass if placeholderKeys()
        // silently stopped finding anything — the four cadences from
        // planning/project-placement-labels.md §4e are the reason it exists.
        List<String> keys = new ArrayList<>();
        for (Class<?> bean : scanBeans()) {
            keys.addAll(placeholderKeys(bean));
        }

        assertThat(keys).contains(
                "vance.cluster.master.distributor-interval",
                "vance.cluster.master.distributor-initial-delay",
                "vance.cluster.master.election-interval",
                "vance.cluster.master.election-initial-delay",
                "vance.cluster.cleanup.interval",
                "vance.cluster.cleanup.initial-delay");
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
