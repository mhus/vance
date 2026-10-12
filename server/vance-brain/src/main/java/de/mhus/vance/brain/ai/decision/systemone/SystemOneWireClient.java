package de.mhus.vance.brain.ai.decision.systemone;

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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The one outbound client for System-One decision endpoints. Builds the
 * request, sends it, parses the answers back into question order and
 * classifies failures. Providers only choose the endpoint and the key.
 *
 * <p>Wire contract (verified live against OpenRouter/Jev and
 * Ollama/nimble, 2026-10-12):
 * <pre>
 * POST {base}/v1/systemone
 * {"model": "…", "state": string|object|array,
 *  "questions": {"&lt;name&gt;": {"type": "noul|choice|score",
 *                             "instructions": …, "criteria": …}}}
 * → {"model": "…", "answers": {"&lt;name&gt;": {"type": …, …}},
 *    "usage": {"input_tokens", "output_tokens", "cost"?}, "id"?}
 * </pre>
 *
 * <p>Parsing is tolerant: unknown fields are ignored (Ollama adds
 * {@code prompt_eval_cached_count}, OpenRouter {@code provider}), numbers
 * are taken as reported (Jev rounds to two places, Ollama does not).
 * Error bodies come in two shapes — {@code {error:{message,code}}}
 * (OpenRouter; the message may itself hold a JSON array of validation
 * issues) and {@code {error:"text"}} (Ollama) — and are passed on as
 * text, never interpreted beyond the HTTP status.
 */
@Component
public class SystemOneWireClient {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final int MAX_ERROR_CHARS = 500;

    private final HttpClient httpClient;

    @Autowired
    public SystemOneWireClient() {
        this(HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build());
    }

    /** Test seam — same logic, injected transport. */
    SystemOneWireClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * Send one decision request to {@code endpoint}.
     *
     * @param apiKey bearer credential, {@code null} for key-less endpoints
     */
    public DecisionResult post(
            URI endpoint, @Nullable String apiKey, AiDecisionConfig config, DecisionRequest request) {
        HttpRequest.Builder http = HttpRequest.newBuilder()
                .uri(endpoint)
                .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        MAPPER.writeValueAsString(requestBody(config.modelName(), request)), StandardCharsets.UTF_8));
        if (apiKey != null) {
            http.header("Authorization", "Bearer " + apiKey);
        }
        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(http.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException e) {
            throw new AiDecisionException(
                    Failure.TIMEOUT,
                    "Decision call to " + config.fullName() + " timed out after " + config.timeoutSeconds() + "s",
                    e);
        } catch (IOException e) {
            throw new AiDecisionException(
                    Failure.TRANSPORT, "Decision call to " + config.fullName() + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiDecisionException(
                    Failure.INTERRUPTED, "Decision call to " + config.fullName() + " interrupted", e);
        }
        if (response.statusCode() != 200) {
            throw classify(
                    response.statusCode(),
                    response.body(),
                    response.headers().firstValue("retry-after").orElse(null),
                    config.fullName());
        }
        return parseResponse(response.body(), request, config.modelName());
    }

    // ──────────────────── request ────────────────────

    /** The wire body. Question order is kept — answers are mapped back by name. */
    static ObjectNode requestBody(String model, DecisionRequest request) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.set("state", MAPPER.valueToTree(request.state()));
        ObjectNode questions = body.putObject("questions");
        for (ZemQuestionDto q : request.questions()) {
            ObjectNode node = questions.putObject(q.getName());
            node.put("type", q.getType().name().toLowerCase(Locale.ROOT));
            node.set("instructions", MAPPER.valueToTree(q.getInstructions()));
            if (q.getCriteria() != null) {
                node.set("criteria", MAPPER.valueToTree(q.getCriteria()));
            }
        }
        return body;
    }

    // ──────────────────── response ────────────────────

    /** Parse a 200 body into a result with answers in request order. */
    static DecisionResult parseResponse(byte[] raw, DecisionRequest request, String requestedModel) {
        JsonNode root;
        try {
            root = MAPPER.readTree(raw);
        } catch (RuntimeException e) {
            throw new AiDecisionException(Failure.PROTOCOL, "Decision response is not JSON: " + truncate(text(raw)), e);
        }
        JsonNode answersNode = root.path("answers");
        if (!answersNode.isObject()) {
            throw new AiDecisionException(
                    Failure.PROTOCOL, "Decision response has no 'answers' object: " + truncate(text(raw)));
        }
        List<ZemAnswerDto> answers = new ArrayList<>(request.questions().size());
        for (ZemQuestionDto q : request.questions()) {
            JsonNode node = answersNode.get(q.getName());
            if (node == null || !node.isObject()) {
                throw new AiDecisionException(
                        Failure.PROTOCOL, "Decision response lacks an answer for question '" + q.getName() + "'");
            }
            answers.add(parseAnswer(q, node));
        }
        JsonNode usage = root.path("usage");
        return new DecisionResult(
                root.path("model").asString(requestedModel),
                answers,
                longOrNull(usage.get("input_tokens")),
                longOrNull(usage.get("output_tokens")),
                doubleOrNull(usage.get("cost")),
                root.path("id").asString(null));
    }

    private static ZemAnswerDto parseAnswer(ZemQuestionDto question, JsonNode node) {
        String wireType = node.path("type").asString("").toLowerCase(Locale.ROOT);
        if ("refusal".equals(wireType)) {
            return ZemAnswerDto.builder()
                    .name(question.getName())
                    .type(ZemAnswerType.REFUSAL)
                    .build();
        }
        String expected = question.getType().name().toLowerCase(Locale.ROOT);
        if (!expected.equals(wireType)) {
            throw new AiDecisionException(
                    Failure.PROTOCOL,
                    "Answer for '" + question.getName() + "' has type '" + wireType + "', expected '" + expected + "'");
        }
        ZemAnswerDto.ZemAnswerDtoBuilder answer =
                ZemAnswerDto.builder().name(question.getName()).confidence(doubleOrNull(node.get("confidence")));
        if (question.getType() == ZemQuestionType.NOUL) {
            return answer.type(ZemAnswerType.NOUL)
                    .noul(requireDouble(node, "noul", question))
                    .build();
        }
        if (question.getType() == ZemQuestionType.CHOICE) {
            String choice = node.path("choice").asString(null);
            if (choice == null) {
                throw new AiDecisionException(
                        Failure.PROTOCOL, "Choice answer for '" + question.getName() + "' has no 'choice'");
            }
            return answer.type(ZemAnswerType.CHOICE)
                    .choice(choice)
                    .probabilities(probabilities(node.get("probabilities")))
                    .build();
        }
        return answer.type(ZemAnswerType.SCORE)
                .score(requireDouble(node, "score", question))
                .probabilities(probabilities(node.get("probabilities")))
                .legend(legend(node.get("legend")))
                .build();
    }

    private static double requireDouble(JsonNode node, String field, ZemQuestionDto question) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()) {
            throw new AiDecisionException(
                    Failure.PROTOCOL, "Answer for '" + question.getName() + "' has no numeric '" + field + "'");
        }
        return value.asDouble();
    }

    private static @Nullable Map<String, Double> probabilities(@Nullable JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            if (e.getValue().isNumber()) {
                out.put(e.getKey(), e.getValue().asDouble());
            }
        }
        return out;
    }

    private static @Nullable Map<String, Object> legend(@Nullable JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            out.put(e.getKey(), MAPPER.treeToValue(e.getValue(), Object.class));
        }
        return out;
    }

    // ──────────────────── errors ────────────────────

    /** Map a non-200 response onto a failure class. */
    static AiDecisionException classify(
            int status, byte @Nullable [] body, @Nullable String retryAfterHeader, String target) {
        String message = errorMessage(body);
        Failure failure;
        if (status == 401 || status == 403) {
            failure = Failure.AUTH;
        } else if (status == 404) {
            failure = Failure.MODEL_NOT_FOUND;
        } else if (status == 429) {
            failure = Failure.RATE_LIMITED;
        } else if (status == 408 || status == 504) {
            failure = Failure.TIMEOUT;
        } else if (status >= 500) {
            failure = Failure.PROVIDER_UNAVAILABLE;
        } else if (isUnknownModel(message)) {
            failure = Failure.MODEL_NOT_FOUND;
        } else {
            failure = Failure.INVALID_REQUEST;
        }
        return new AiDecisionException(
                failure,
                "Decision call to " + target + " failed (HTTP " + status + "): " + message,
                status,
                retryAfter(retryAfterHeader),
                null);
    }

    /** OpenRouter answers an unknown model with 400 "Model … does not exist". */
    private static boolean isUnknownModel(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("does not exist") || lower.contains("not found");
    }

    private static String errorMessage(byte @Nullable [] body) {
        if (body == null || body.length == 0) {
            return "(no error body)";
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode error = root.path("error");
            if (error.isObject()) {
                String message = error.path("message").asString(null);
                if (message != null) {
                    return truncate(message);
                }
            }
            if (error.isString()) {
                return truncate(error.asString());
            }
            String detail = root.path("detail").asString(null);
            if (detail != null) {
                return truncate(detail);
            }
        } catch (RuntimeException ignored) {
            // not JSON (Perplexity can answer a 504 with HTML) — use the raw text
        }
        return truncate(text(body));
    }

    /** {@code Retry-After} in seconds; the HTTP-date form is ignored. */
    private static @Nullable Duration retryAfter(@Nullable String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            long seconds = Long.parseLong(header.trim());
            return seconds >= 0 ? Duration.ofSeconds(seconds) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ──────────────────── helpers ────────────────────

    private static @Nullable Long longOrNull(@Nullable JsonNode node) {
        return node != null && node.isNumber() ? node.asLong() : null;
    }

    private static @Nullable Double doubleOrNull(@Nullable JsonNode node) {
        return node != null && node.isNumber() ? node.asDouble() : null;
    }

    private static String text(byte[] raw) {
        return new String(raw, StandardCharsets.UTF_8);
    }

    private static String truncate(String s) {
        return s.length() <= MAX_ERROR_CHARS ? s : s.substring(0, MAX_ERROR_CHARS) + "…";
    }
}
