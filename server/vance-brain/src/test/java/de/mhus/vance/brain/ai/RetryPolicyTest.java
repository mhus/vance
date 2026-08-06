package de.mhus.vance.brain.ai;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.RateLimitException;
import java.time.Duration;
import java.util.List;
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

    @Test
    void badGateway_isRetriable() {
        // Field case: glm-5.2 behind a proxy answered 502, langchain4j
        // mapped it to InternalServerException whose message is the bare
        // reason phrase — no "502" anywhere for a substring match. The
        // chain got exhausted and the think-process went BLOCKED mid-turn.
        Throwable http = new HttpException(502, "Bad Gateway");
        Throwable mapped = new InternalServerException(http);
        Throwable wrapped = new RuntimeException(
                "Frankie streaming failed: All 1 chat-model chain entries exhausted", mapped);

        assertThat(RetryPolicy.DEFAULT.shouldRetry(wrapped)).isTrue();
    }

    @Test
    void badGatewayReasonPhrase_isRetriableWithoutTheTypedMarker() {
        // Same failure arriving outside langchain4j's exception mapper.
        assertThat(RetryPolicy.DEFAULT.shouldRetry(
                new RuntimeException("Bad Gateway"))).isTrue();
        assertThat(RetryPolicy.DEFAULT.shouldRetry(
                new RuntimeException("504 Gateway Timeout"))).isTrue();
    }

    @Test
    void rateLimitException_isRetriableByType() {
        assertThat(RetryPolicy.DEFAULT.shouldRetry(
                new RateLimitException(new HttpException(429, "Too Many Requests")))).isTrue();
    }

    @Test
    void invalidRequest_isNotRetriableEvenWhenTheMessageMatchesAPattern() {
        // langchain4j's typed verdict beats substring guessing: a 400 is
        // the model's fault and retrying it just burns the budget, no
        // matter which words the provider put in the message.
        Throwable badRequest = new InvalidRequestException(
                new HttpException(400, "invalid parameter: timeout must be positive"));

        assertThat(RetryPolicy.DEFAULT.shouldRetry(badRequest)).isFalse();
    }

    @Test
    void authenticationFailure_isNotRetriable() {
        assertThat(RetryPolicy.DEFAULT.shouldRetry(
                new AuthenticationException(new HttpException(401, "Unauthorized")))).isFalse();
    }

    @Test
    void retriableMarkerWinsOverNonRetriableInTheSameChain() {
        // Providers do wrap a transient transport failure in a
        // request-shaped exception; the attempt budget bounds the cost of
        // reading such a chain optimistically.
        Throwable chain = new InvalidRequestException(
                new InternalServerException(new HttpException(503, "Service Unavailable")));

        assertThat(RetryPolicy.DEFAULT.shouldRetry(chain)).isTrue();
    }

    @Test
    void emptyPatternList_stillHonoursTheTypedMarker() {
        RetryPolicy noPatterns = new RetryPolicy(
                3, Duration.ofSeconds(1), Duration.ofSeconds(2), List.of());

        assertThat(noPatterns.shouldRetry(
                new InternalServerException(new HttpException(502, "Bad Gateway")))).isTrue();
        assertThat(noPatterns.shouldRetry(new RuntimeException("Bad Gateway"))).isFalse();
    }
}
