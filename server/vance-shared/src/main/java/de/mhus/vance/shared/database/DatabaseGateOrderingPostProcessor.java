package de.mhus.vance.shared.database;

import de.mhus.vance.shared.schema.SchemaMigrationService;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * Puts the two database gates in front of the repository layer.
 *
 * <p>Adds {@code depends-on} to every Spring Data Mongo repository bean
 * definition, naming whichever gates the context carries:
 * {@link DatabaseIdentityGuard} — is this our database at all — and
 * {@link SchemaMigrationService} — is it in the shape this build expects.
 * Neither is asked for by anyone; without this, a service could read
 * through a repository before either question was answered, and nobody
 * would have to sprinkle {@code @DependsOn} to notice.
 *
 * <p>Lives in this package rather than with the migrator because the
 * identity guard is the broader of the two: the delivery service carries
 * it without a migrator, and it scans this package to get both.
 *
 * <p>Matching is on the bean definition's class name rather than on bean
 * types: a {@code MongoRepositoryFactoryBean} definition can be recognised
 * without instantiating anything, which is the only option inside a
 * {@link BeanFactoryPostProcessor}. If Spring Data ever renames the class,
 * the log line below says so instead of the ordering going quietly
 * missing.
 */
@Component
@Slf4j
public class DatabaseGateOrderingPostProcessor implements BeanFactoryPostProcessor, Ordered {

    private static final String MONGO_REPOSITORY_FACTORY_BEAN =
            "org.springframework.data.mongodb.repository.support.MongoRepositoryFactoryBean";

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        // Both are optional and independent: the delivery service carries
        // the identity guard without a migrator, and a context that only
        // reads may carry neither.
        List<String> gates = Stream.of(
                        DatabaseIdentityGuard.BEAN_NAME, SchemaMigrationService.BEAN_NAME)
                .filter(beanFactory::containsBeanDefinition)
                .toList();
        if (gates.isEmpty()) {
            return;
        }
        int wired = 0;
        for (String name : beanFactory.getBeanDefinitionNames()) {
            BeanDefinition definition = beanFactory.getBeanDefinition(name);
            if (!MONGO_REPOSITORY_FACTORY_BEAN.equals(definition.getBeanClassName())) {
                continue;
            }
            definition.setDependsOn(withGates(definition.getDependsOn(), gates));
            wired++;
        }
        if (wired == 0) {
            log.warn("Schema migrations: found no Mongo repository bean definitions to order after "
                    + "{}. Repositories may now be created before the database is checked — check "
                    + "whether Spring Data renamed {}.", gates, MONGO_REPOSITORY_FACTORY_BEAN);
        } else {
            log.debug("Schema migrations: {} Mongo repository bean(s) will wait for {}",
                    wired, gates);
        }
    }

    private static String[] withGates(String @Nullable [] existing, List<String> gates) {
        Set<String> merged = new LinkedHashSet<>();
        if (existing != null) {
            merged.addAll(Arrays.asList(existing));
        }
        merged.addAll(gates);
        return merged.toArray(String[]::new);
    }

    @Override
    public int getOrder() {
        // Same precedence Spring Boot uses for its own depends-on processors: run
        // before anything that might freeze the definitions.
        return 0;
    }
}
