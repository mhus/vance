package de.mhus.vance.brain.ai.decision.systemone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.zem.ZemAnswerDto;
import de.mhus.vance.api.zem.ZemAnswerType;
import de.mhus.vance.api.zem.ZemQuestionDto;
import de.mhus.vance.api.zem.ZemQuestionType;
import de.mhus.vance.brain.ai.decision.AiDecisionConfig;
import de.mhus.vance.brain.ai.decision.AiDecisionException;
import de.mhus.vance.brain.ai.decision.AiDecisionException.Failure;
import de.mhus.vance.brain.ai.decision.DecisionRequest;
import de.mhus.vance.brain.ai.decision.DecisionResult;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins the System-One wire against the real responses recorded in the
 * Z0 live spike ({@code planning/zem-z0/out/}, copied as test
 * resources): OpenRouter/Jev and Ollama/nimble differ in rounding,
 * extra fields, cost and error shapes, and the client must read both.
 */
class SystemOneWireClientTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final URI ENDPOINT = URI.create("https://openrouter.ai/api/v1/systemone");
    private static final AiDecisionConfig CONFIG =
            new AiDecisionConfig("openai", "openrouter", "typesafe/jev-1.13", "sk-or-test", null, 10);

    /** The three questions of the spike's "basic" case. */
    private static DecisionRequest basicRequest() {
        Map<String, Object> intentCriteria = new LinkedHashMap<>();
        intentCriteria.put("refund", "The customer requests a refund.");
        intentCriteria.put("duplicate_charge", "The customer reports being charged more than once.");
        intentCriteria.put("other", "Another request.");
        return new DecisionRequest(
                Map.of("message", "My card was charged twice for the same order and I need a refund."),
                List.of(
                        ZemQuestionDto.builder()
                                .name("is_billing")
                                .type(ZemQuestionType.NOUL)
                                .instructions("Is this a billing-related request?")
                                .criteria(Map.of("true", "Concerns money.", "false", "Does not."))
                                .build(),
                        ZemQuestionDto.builder()
                                .name("intent")
                                .type(ZemQuestionType.CHOICE)
                                .instructions("Which intent best matches `message`?")
                                .criteria(intentCriteria)
                                .build(),
                        ZemQuestionDto.builder()
                                .name("urgency")
                                .type(ZemQuestionType.SCORE)
                                .instructions("How urgent is the request?")
                                .criteria(List.of("Can wait", "Needs attention soon", "Urgent"))
                                .build()));
    }

    // ──── request ──────────────────────────────────────────────────────

    @Test
    void request_body_uses_lowercase_types_and_keeps_question_and_option_order() {
        JsonNode body = SystemOneWireClient.requestBody("typesafe/jev-1.13", basicRequest());

        assertThat(body.path("model").asString()).isEqualTo("typesafe/jev-1.13");
        assertThat(body.path("state").path("message").asString()).startsWith("My card");
        assertThat(body.path("questions").propertyNames()).containsExactly("is_billing", "intent", "urgency");
        assertThat(body.path("questions").path("is_billing").path("type").asString())
                .isEqualTo("noul");
        assertThat(body.path("questions").path("intent").path("criteria").propertyNames())
                .containsExactly("refund", "duplicate_charge", "other");
        assertThat(body.path("questions")
                        .path("urgency")
                        .path("criteria")
                        .get(2)
                        .asString())
                .isEqualTo("Urgent");
    }

    @Test
    void request_body_omits_absent_criteria() {
        DecisionRequest request = new DecisionRequest(
                "charged twice",
                List.of(ZemQuestionDto.builder()
                        .name("q")
                        .type(ZemQuestionType.NOUL)
                        .instructions(Map.of("question", "Is it billing?"))
                        .build()));

        JsonNode q = SystemOneWireClient.requestBody("nimble", request)
                .path("questions")
                .path("q");

        assertThat(q.has("criteria")).isFalse();
        assertThat(q.path("instructions").path("question").asString()).isEqualTo("Is it billing?");
    }

    // ──── response ─────────────────────────────────────────────────────

    @Test
    void parses_openrouter_response_with_cost_and_generation_id() throws IOException {
        DecisionResult result = SystemOneWireClient.parseResponse(
                fixture("openrouter-basic.json"), basicRequest(), "typesafe/jev-1.13");

        assertThat(result.model()).isEqualTo("typesafe/jev-1.13-20260917");
        assertThat(result.inputTokens()).isEqualTo(456L);
        assertThat(result.costUsd()).isEqualTo(1.9152e-05);
        assertThat(result.requestId()).startsWith("gen-dec-");
        assertThat(result.answers())
                .extracting(ZemAnswerDto::getName)
                .containsExactly("is_billing", "intent", "urgency");

        ZemAnswerDto noul = result.answers().get(0);
        assertThat(noul.getType()).isEqualTo(ZemAnswerType.NOUL);
        assertThat(noul.getNoul()).isEqualTo(0.99);
        assertThat(noul.getConfidence()).isNull(); // NOUL never carries one on the wire

        ZemAnswerDto choice = result.answers().get(1);
        assertThat(choice.getChoice()).isEqualTo("duplicate_charge");
        assertThat(choice.getConfidence()).isEqualTo(0.91);
        assertThat(choice.getProbabilities())
                .containsEntry("duplicate_charge", 0.94)
                .containsEntry("other", 0.0);

        ZemAnswerDto score = result.answers().get(2);
        assertThat(score.getScore()).isEqualTo(1.17);
        assertThat(score.getProbabilities()).containsKeys("0", "1", "2");
        assertThat(score.getLegend()).containsEntry("2", "Urgent");
    }

    @Test
    void parses_ollama_response_without_cost_and_with_extra_fields() throws IOException {
        DecisionResult result =
                SystemOneWireClient.parseResponse(fixture("ollama-basic.json"), basicRequest(), "nimble");

        assertThat(result.model()).isEqualTo("nimble");
        assertThat(result.costUsd()).isNull();
        assertThat(result.requestId()).isNull();
        assertThat(result.outputTokens()).isZero();
        assertThat(result.answers().get(1).getChoice()).isEqualTo("duplicate_charge");
        assertThat(result.answers().get(0).getNoul()).isBetween(0.99, 1.0); // unrounded on this wire
    }

    @Test
    void structured_score_levels_come_back_as_maps_in_the_legend() throws IOException {
        DecisionRequest request = new DecisionRequest(
                "x",
                List.of(ZemQuestionDto.builder()
                        .name("q")
                        .type(ZemQuestionType.SCORE)
                        .instructions("Rate urgency.")
                        .criteria(List.of(
                                Map.of("label", "low", "what", "can wait a week"),
                                Map.of("label", "high", "what", "needs action today")))
                        .build()));

        DecisionResult result = SystemOneWireClient.parseResponse(
                fixture("openrouter-score-objects.json"), request, "typesafe/jev-1.13");

        assertThat(result.answers().get(0).getLegend().get("1"))
                .isEqualTo(Map.of("label", "high", "what", "needs action today"));
    }

    @Test
    void missing_answer_is_a_protocol_failure() {
        byte[] body = "{\"model\":\"m\",\"answers\":{\"is_billing\":{\"type\":\"noul\",\"noul\":0.5}}}"
                .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> SystemOneWireClient.parseResponse(body, basicRequest(), "m"))
                .isInstanceOfSatisfying(
                        AiDecisionException.class, e -> assertThat(e.failure()).isEqualTo(Failure.PROTOCOL))
                .hasMessageContaining("'intent'");
    }

    @Test
    void answer_of_the_wrong_type_is_a_protocol_failure() {
        byte[] body = ("{\"answers\":{\"is_billing\":{\"type\":\"score\",\"score\":1},"
                        + "\"intent\":{\"type\":\"choice\",\"choice\":\"other\"},"
                        + "\"urgency\":{\"type\":\"score\",\"score\":1}}}")
                .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> SystemOneWireClient.parseResponse(body, basicRequest(), "m"))
                .isInstanceOfSatisfying(
                        AiDecisionException.class, e -> assertThat(e.failure()).isEqualTo(Failure.PROTOCOL));
    }

    @Test
    void refusal_is_an_answer_not_a_failure() {
        byte[] body = ("{\"answers\":{\"is_billing\":{\"type\":\"refusal\"},"
                        + "\"intent\":{\"type\":\"choice\",\"choice\":\"other\"},"
                        + "\"urgency\":{\"type\":\"score\",\"score\":0}}}")
                .getBytes(StandardCharsets.UTF_8);

        DecisionResult result = SystemOneWireClient.parseResponse(body, basicRequest(), "m");

        assertThat(result.model()).isEqualTo("m"); // falls back to the requested id
        assertThat(result.answers().get(0).getType()).isEqualTo(ZemAnswerType.REFUSAL);
        assertThat(result.answers().get(0).getNoul()).isNull();
    }

    // ──── errors ───────────────────────────────────────────────────────

    @Test
    void openrouter_limit_error_is_an_invalid_request_with_the_detail() throws IOException {
        AiDecisionException e = SystemOneWireClient.classify(
                400, fixture("openrouter-error-too-many-choices.json"), null, "openrouter:typesafe/jev-1.13");

        assertThat(e.failure()).isEqualTo(Failure.INVALID_REQUEST);
        assertThat(e.retryable()).isFalse();
        assertThat(e.getMessage()).contains("HTTP 400").contains("at most 255 choices");
    }

    @Test
    void openrouter_validation_error_keeps_the_embedded_issue_text() throws IOException {
        AiDecisionException e = SystemOneWireClient.classify(400, fixture("openrouter-error-zod.json"), null, "t");

        assertThat(e.failure()).isEqualTo(Failure.INVALID_REQUEST);
        assertThat(e.getMessage()).contains("invalid_union");
    }

    @Test
    void unknown_model_is_recognised_on_both_wires() throws IOException {
        assertThat(SystemOneWireClient.classify(400, fixture("openrouter-error-unknown-model.json"), null, "t")
                        .failure())
                .isEqualTo(Failure.MODEL_NOT_FOUND);
        assertThat(SystemOneWireClient.classify(404, fixture("ollama-error-unknown-model.json"), null, "t")
                        .failure())
                .isEqualTo(Failure.MODEL_NOT_FOUND);
    }

    @Test
    void ollama_plain_string_error_is_read() throws IOException {
        AiDecisionException e = SystemOneWireClient.classify(400, fixture("ollama-error-candidates.json"), null, "t");

        assertThat(e.failure()).isEqualTo(Failure.INVALID_REQUEST);
        assertThat(e.getMessage()).contains("2–26 candidates");
    }

    @Test
    void rate_limit_carries_retry_after_and_html_bodies_survive() {
        AiDecisionException limited = SystemOneWireClient.classify(429, new byte[0], "7", "t");
        assertThat(limited.failure()).isEqualTo(Failure.RATE_LIMITED);
        assertThat(limited.retryable()).isTrue();
        assertThat(limited.retryAfter()).isEqualTo(Duration.ofSeconds(7));

        AiDecisionException gateway = SystemOneWireClient.classify(
                504, "<html>Gateway Timeout</html>".getBytes(StandardCharsets.UTF_8), "soon", "t");
        assertThat(gateway.failure()).isEqualTo(Failure.TIMEOUT);
        assertThat(gateway.retryAfter()).isNull();
        assertThat(gateway.getMessage()).contains("Gateway Timeout");

        assertThat(SystemOneWireClient.classify(529, null, null, "t").failure())
                .isEqualTo(Failure.PROVIDER_UNAVAILABLE);
        assertThat(SystemOneWireClient.classify(401, null, null, "t").failure()).isEqualTo(Failure.AUTH);
    }

    // ──── transport ────────────────────────────────────────────────────

    @Test
    void post_sends_bearer_key_and_parses_the_answer() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<byte[]> ok = response(200, fixture("openrouter-basic.json"));
        when(http.<byte[]>send(any(HttpRequest.class), any())).thenReturn(ok);
        ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);

        DecisionResult result = new SystemOneWireClient(http).post(ENDPOINT, "sk-or-test", CONFIG, basicRequest());

        org.mockito.Mockito.verify(http).send(sent.capture(), any());
        assertThat(sent.getValue().uri()).isEqualTo(ENDPOINT);
        assertThat(sent.getValue().headers().firstValue("Authorization")).contains("Bearer sk-or-test");
        assertThat(sent.getValue().timeout()).contains(Duration.ofSeconds(10));
        assertThat(result.answers()).hasSize(3);
    }

    @Test
    void post_without_key_sends_no_authorization_header() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<byte[]> ok = response(200, fixture("ollama-basic.json"));
        when(http.<byte[]>send(any(HttpRequest.class), any())).thenReturn(ok);
        ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
        AiDecisionConfig local = new AiDecisionConfig("ollama", "ollama", "nimble", null, null, 30);

        new SystemOneWireClient(http)
                .post(URI.create("http://localhost:11434/v1/systemone"), null, local, basicRequest());

        org.mockito.Mockito.verify(http).send(sent.capture(), any());
        assertThat(sent.getValue().headers().firstValue("Authorization")).isEmpty();
    }

    @Test
    void timeout_maps_to_a_retryable_timeout() throws Exception {
        HttpClient http = mock(HttpClient.class);
        when(http.<byte[]>send(any(HttpRequest.class), any())).thenThrow(new HttpTimeoutException("slow"));

        assertThatThrownBy(() -> new SystemOneWireClient(http).post(ENDPOINT, "k", CONFIG, basicRequest()))
                .isInstanceOfSatisfying(AiDecisionException.class, e -> {
                    assertThat(e.failure()).isEqualTo(Failure.TIMEOUT);
                    assertThat(e.retryable()).isTrue();
                });
    }

    @Test
    void non_200_response_is_classified() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<byte[]> limited =
                response(429, "{\"error\":{\"message\":\"slow down\",\"code\":429}}".getBytes(StandardCharsets.UTF_8));
        when(limited.headers()).thenReturn(HttpHeaders.of(Map.of("retry-after", List.of("2")), (a, b) -> true));
        when(http.<byte[]>send(any(HttpRequest.class), any())).thenReturn(limited);

        assertThatThrownBy(() -> new SystemOneWireClient(http).post(ENDPOINT, "k", CONFIG, basicRequest()))
                .isInstanceOfSatisfying(AiDecisionException.class, e -> {
                    assertThat(e.failure()).isEqualTo(Failure.RATE_LIMITED);
                    assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(e.httpStatus()).isEqualTo(429);
                    assertThat(e.getMessage()).contains("slow down");
                });
    }

    // ──── helpers ──────────────────────────────────────────────────────

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = SystemOneWireClientTest.class.getResourceAsStream(name)) {
            assertThat(in).as("fixture %s", name).isNotNull();
            return in.readAllBytes();
        }
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<byte[]> response(int status, byte[] body) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (a, b) -> true));
        return response;
    }
}
