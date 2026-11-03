package de.mhus.vance.shared.redis;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Provides the in-process {@link VanceRedisMessagingService} when no other implementation is
 * registered. The cloud edition ships a Redis-backed one in its own module, which is processed
 * first and so takes the slot; a single node — the community edition — ends up here and needs
 * neither Redis nor a client library for it.
 */
@AutoConfiguration
public class LocalMessagingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(VanceRedisMessagingService.class)
    public LocalMessagingService localMessagingService() {
        return new LocalMessagingService();
    }
}
