package de.mhus.vance.addon.brain.contacts;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

/**
 * Every bean of this addon must be constructible by Spring: one constructor,
 * or exactly one marked {@link Autowired}. A second constructor for tests
 * without the marker compiles, passes every unit test, and only fails when a
 * brain boots ("No default constructor found").
 */
class ContactsBeanConstructorTest {

    @Test
    void everyBeanHasAnUnambiguousConstructor() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(true);
        List<String> ambiguous = new ArrayList<>();
        int beans = 0;
        for (BeanDefinition def : scanner.findCandidateComponents("de.mhus.vance.addon.brain.contacts")) {
            Class<?> type = Class.forName(def.getBeanClassName());
            beans++;
            Constructor<?>[] constructors = type.getDeclaredConstructors();
            long autowired = Arrays.stream(constructors)
                    .filter(c -> c.isAnnotationPresent(Autowired.class))
                    .count();
            if (constructors.length > 1 && autowired != 1) ambiguous.add(type.getSimpleName());
        }
        assertThat(beans).isGreaterThan(20);
        assertThat(ambiguous).isEmpty();
    }
}
