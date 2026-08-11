package de.mhus.vance.shared.schema;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * Slots {@link SchemaMigrationService} between the Mongo infrastructure and the
 * repository layer by adding {@code depends-on: schemaMigrationService} to every
 * Spring Data Mongo repository bean definition.
 *
 * <p>Why this and not annotations: the guarantee we want is "no service reads a
 * shape before it is migrated", and services reach data through repositories.
 * Making the repositories wait covers all of them at once, so no individual bean
 * has to carry a {@code @DependsOn} — the requirement cannot be forgotten in a
 * new service. The migrator itself only needs {@code MongoTemplate}, which sits
 * below the repositories, so there is no cycle. That is also the reason
 * {@link SchemaMigration} implementations must not use beans: at the time they
 * run, the layer above {@code MongoTemplate} does not exist yet.
 *
 * <p>Matching is on the bean definition's class name rather than on bean types:
 * {@code MongoRepositoryFactoryBean} definitions can be recognised without
 * resolving their product type, which would mean instantiating factory beans in a
 * {@link BeanFactoryPostProcessor}. If Spring Data ever renames the class, the
 * WARN below fires instead of the ordering silently disappearing.
 */
@Component
@Slf4j
public class SchemaMigrationOrderingPostProcessor implements BeanFactoryPostProcessor, Ordered {

    private static final String MONGO_REPOSITORY_FACTORY_BEAN =
            "org.springframework.data.mongodb.repository.support.MongoRepositoryFactoryBean";

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        if (!beanFactory.containsBeanDefinition(SchemaMigrationService.BEAN_NAME)) {
            // Not every context that sees this class runs migrations.
            return;
        }
        int wired = 0;
        for (String name : beanFactory.getBeanDefinitionNames()) {
            BeanDefinition definition = beanFactory.getBeanDefinition(name);
            if (!MONGO_REPOSITORY_FACTORY_BEAN.equals(definition.getBeanClassName())) {
                continue;
            }
            definition.setDependsOn(withMigrator(definition.getDependsOn()));
            wired++;
        }
        if (wired == 0) {
            log.warn("Schema migrations: found no Mongo repository bean definitions to order after "
                    + "'{}'. Repositories may now be created before migrations run — check whether "
                    + "Spring Data renamed {}.",
                    SchemaMigrationService.BEAN_NAME, MONGO_REPOSITORY_FACTORY_BEAN);
        } else {
            log.debug("Schema migrations: {} Mongo repository bean(s) will wait for '{}'",
                    wired, SchemaMigrationService.BEAN_NAME);
        }
    }

    private static String[] withMigrator(String @Nullable [] existing) {
        Set<String> merged = new LinkedHashSet<>();
        if (existing != null) {
            merged.addAll(Arrays.asList(existing));
        }
        merged.add(SchemaMigrationService.BEAN_NAME);
        return merged.toArray(String[]::new);
    }

    @Override
    public int getOrder() {
        // Same precedence Spring Boot uses for its own depends-on processors: run
        // before anything that might freeze the definitions.
        return 0;
    }
}
