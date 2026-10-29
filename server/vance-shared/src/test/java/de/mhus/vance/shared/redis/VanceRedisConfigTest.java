package de.mhus.vance.shared.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

class VanceRedisConfigTest {

    @Test
    void commandTimeoutIsShortByDefault() {
        LettuceClientConfiguration config = VanceRedisConfig.clientConfiguration(new VanceRedisProperties());

        assertThat(config.getCommandTimeout()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void timeoutsComeFromTheProperties() {
        VanceRedisProperties props = new VanceRedisProperties();
        props.setCommandTimeout(Duration.ofMillis(750));
        props.setConnectTimeout(Duration.ofMillis(900));

        LettuceClientConfiguration config = VanceRedisConfig.clientConfiguration(props);

        assertThat(config.getCommandTimeout()).isEqualTo(Duration.ofMillis(750));
        assertThat(config.getClientOptions().orElseThrow().getSocketOptions().getConnectTimeout())
                .isEqualTo(Duration.ofMillis(900));
    }

    /** A Redis that accepts the connection and never answers must fail a publish within the timeout. */
    @Test
    void publishToAnUnresponsiveRedisFailsWithinTheCommandTimeout() throws Exception {
        try (ServerSocket blackHole = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            VanceRedisProperties props = new VanceRedisProperties();
            props.setHost("127.0.0.1");
            props.setPort(blackHole.getLocalPort());
            props.setCommandTimeout(Duration.ofMillis(300));
            props.setConnectTimeout(Duration.ofMillis(300));
            LettuceConnectionFactory factory = new LettuceConnectionFactory(
                    new org.springframework.data.redis.connection.RedisStandaloneConfiguration(
                            props.getHost(), props.getPort()),
                    VanceRedisConfig.clientConfiguration(props));
            factory.afterPropertiesSet();
            StringRedisTemplate template = new StringRedisTemplate(factory);
            template.afterPropertiesSet();
            long start = System.nanoTime();

            assertThatThrownBy(() -> template.convertAndSend("vance:t:documents.changed", "x"))
                    .isInstanceOf(RuntimeException.class);

            assertThat(Duration.ofNanos(System.nanoTime() - start))
                    .as("fails fast instead of waiting for Lettuce's 60 s default")
                    .isLessThan(Duration.ofSeconds(10));
            factory.destroy();
        }
    }
}
