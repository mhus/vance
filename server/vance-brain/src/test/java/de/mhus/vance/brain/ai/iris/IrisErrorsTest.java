package de.mhus.vance.brain.ai.iris;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.brain.ai.RetryPolicy;
import dev.langchain4j.exception.HttpException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class IrisErrorsTest {

    private static HttpException http(int status, String code, String message, String extra) {
        return new HttpException(
                status, "{\"error\":{\"code\":\"" + code + "\",\"message\":\"" + message + "\"" + extra + "}}");
    }

    @Test
    void anEmptyQuotaIsAFinalRefusalThatNoRetryTouches() {
        RuntimeException mapped = IrisErrors.map(http(402, "quota_exhausted", "the quota is exhausted", ""));

        assertThat(mapped).isInstanceOf(IrisRefusedException.class);
        IrisRefusedException refused = (IrisRefusedException) mapped;
        assertThat(refused.code()).isEqualTo("quota_exhausted");
        assertThat(refused.status()).isEqualTo(402);
        assertThat(refused.getMessage()).contains("quota_exhausted").contains("the quota is exhausted");
        // The 429-for-hours must not be retried with backoff, and neither must this.
        assertThat(RetryPolicy.DEFAULT.shouldRetry(refused)).isFalse();
    }

    @Test
    void aFullWindowIsFinalEvenThoughItIsA429AndSaysWhenItIsOverAgain() {
        RuntimeException mapped = IrisErrors.map(
                http(429, "window_exhausted", "the 5-hour window is used up", ",\"retry_after_seconds\":12000"));

        IrisRefusedException refused = (IrisRefusedException) mapped;
        assertThat(refused.retryAfter()).isEqualTo(Duration.ofSeconds(12000));
        assertThat(refused.getMessage()).contains("3 h 20 min");
        assertThat(RetryPolicy.DEFAULT.shouldRetry(refused)).isFalse();
    }

    @Test
    void everyFinalCodeBecomesARefusal() {
        for (String code : new String[] {
            "quota_exhausted",
            "window_exhausted",
            "model_not_allowed",
            "model_not_found",
            "wrong_kind",
            "wrong_wire",
            "tenant_disabled",
            "invalid_key",
            "invalid_request"
        }) {
            assertThat(IrisErrors.map(http(400, code, "x", ""))).as(code).isInstanceOf(IrisRefusedException.class);
        }
    }

    @Test
    void transientProblemsStayWhatTheyWereSoTheyAreRetriedByStatus() {
        for (String code : new String[] {
            "too_many_concurrent", "rate_limited", "no_healthy_target", "accounting_unavailable", "not_ready"
        }) {
            HttpException original = http(code.equals("rate_limited") ? 429 : 503, code, "x", "");
            assertThat(IrisErrors.map(original)).as(code).isSameAs(original);
        }
    }

    @Test
    void bodiesThatAreNotIrisErrorsPassThrough() {
        HttpException plain = new HttpException(502, "Bad Gateway");
        HttpException broken = new HttpException(500, "{not json");
        HttpException otherShape = new HttpException(400, "{\"error\":\"nope\"}");

        assertThat(IrisErrors.map(plain)).isSameAs(plain);
        assertThat(IrisErrors.map(broken)).isSameAs(broken);
        assertThat(IrisErrors.map(otherShape)).isSameAs(otherShape);
    }

    @Test
    void aBodyWithTextInFrontOfTheJsonIsStillRead() {
        // langchain4j may prefix its own text to the provider body.
        HttpException prefixed =
                new HttpException(402, "status 402: {\"error\":{\"code\":\"quota_exhausted\",\"message\":\"empty\"}}");

        assertThat(IrisErrors.map(prefixed)).isInstanceOf(IrisRefusedException.class);
    }

    @Test
    void durationsReadNaturally() {
        assertThat(IrisRefusedException.humanize(Duration.ofSeconds(45))).isEqualTo("45 s");
        assertThat(IrisRefusedException.humanize(Duration.ofMinutes(7))).isEqualTo("7 min");
        assertThat(IrisRefusedException.humanize(Duration.ofMinutes(125))).isEqualTo("2 h 5 min");
    }
}
