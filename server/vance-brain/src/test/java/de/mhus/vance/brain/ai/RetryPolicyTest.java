package de.mhus.vance.brain.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    @Test
    void timeout_isRetriable() {
        // java.net.http.HttpTimeoutException message.
        assertThat(RetryPolicy.DEFAULT.shouldRetry(
                new RuntimeException("request timed out"))).isTrue();
    }

    @Test
    void wrappedTimeout_isRetriable() {
        Throwable cause = new java.net.http.HttpTimeoutException("request timed out");
        Throwable wrapped = new RuntimeException("Frankie streaming failed", cause);

        assertThat(RetryPolicy.DEFAULT.shouldRetry(wrapped)).isTrue();
    }

    @Test
    void rateLimit_isRetriable() {
        assertThat(RetryPolicy.DEFAULT.shouldRetry(
                new RuntimeException("429 rate limit exceeded"))).isTrue();
    }

    @Test
    void genericError_isNotRetriable() {
        assertThat(RetryPolicy.DEFAULT.shouldRetry(
                new RuntimeException("invalid request: bad tool schema"))).isFalse();
    }
}
