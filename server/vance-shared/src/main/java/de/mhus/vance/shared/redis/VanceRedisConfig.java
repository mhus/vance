package de.mhus.vance.shared.redis;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Spring wiring for the Vance cross-pod messaging Redis. Three Beans:
 *
 * <ul>
 *   <li>{@link LettuceConnectionFactory} — Lettuce is Spring Data Redis'
 *       default, async/Netty under the hood. We provide it explicitly so
 *       Spring Boot's auto-config (which would key off
 *       {@code spring.data.redis.*}) doesn't compete with our own
 *       {@code vance.redis.*} namespace.</li>
 *   <li>{@link StringRedisTemplate} — for {@code convertAndSend} on the
 *       pub/sub publisher side.</li>
 *   <li>{@link RedisMessageListenerContainer} — Lettuce pub/sub
 *       subscriber side; consumers register their listeners through
 *       {@link VanceRedisMessagingService}.</li>
 * </ul>
 *
 * <p>Bean name prefix {@code vance*} to keep it isolated from any other
 * Redis usage that might pick up Spring's default
 * {@code redisConnectionFactory} name in the future.
 */
@Configuration
@EnableConfigurationProperties(VanceRedisProperties.class)
@ConditionalOnProperty(prefix = "vance.redis", name = "enabled", havingValue = "true")
public class VanceRedisConfig {

    @Bean(name = "vanceRedisConnectionFactory")
    public LettuceConnectionFactory vanceRedisConnectionFactory(VanceRedisProperties props) {
        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration();
        standalone.setHostName(props.getHost());
        standalone.setPort(props.getPort());
        standalone.setDatabase(props.getDatabase());
        if (props.getPassword() != null && !props.getPassword().isBlank()) {
            standalone.setPassword(props.getPassword());
        }
        LettuceClientConfiguration.LettuceClientConfigurationBuilder clientBuilder =
                LettuceClientConfiguration.builder();
        if (props.isSsl()) clientBuilder.useSsl();
        return new LettuceConnectionFactory(standalone, clientBuilder.build());
    }

    @Bean(name = "vanceRedisTemplate")
    public StringRedisTemplate vanceRedisTemplate(LettuceConnectionFactory vanceRedisConnectionFactory) {
        return new StringRedisTemplate(vanceRedisConnectionFactory);
    }

    @Bean(name = "vanceRedisMessageListenerContainer")
    public RedisMessageListenerContainer vanceRedisMessageListenerContainer(
            @org.springframework.beans.factory.annotation.Qualifier("vanceRedisConnectionFactory")
                    RedisConnectionFactory factory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        return container;
    }
}
