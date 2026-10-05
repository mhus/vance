package de.mhus.vance.brain.ai.image.openrouter;

import de.mhus.vance.brain.ai.ProviderType;
import de.mhus.vance.brain.ai.image.AiImageConfig;
import de.mhus.vance.brain.ai.image.AiImageException;
import de.mhus.vance.brain.ai.image.AiImageModelProvider;
import de.mhus.vance.brain.ai.image.ImageMimeTypeSniffer;
import de.mhus.vance.shared.document.ImageDestinationStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Dedicated image-generation provider for the {@code openrouter}
 * named instance — OpenRouter's own Image API
 * ({@code POST /api/v1/images}), not the OpenAI images wire.
 *
 * <p><b>Why a dedicated adapter.</b> The instance runs with
 * {@code wireType: openai} because its <em>chat</em> endpoint speaks
 * the OpenAI wire, but its image API does not: the documented route is
 * {@code /images} (the OpenAI-style {@code /images/generations} exists
 * but is undocumented and can disappear), the request carries a
 * native {@code aspect_ratio} instead of pixel sizes, and the response
 * reports the real cost in {@code usage.cost} — which this provider
 * passes through as {@code costUsd} metadata so Fenchurch books the
 * vendor's number instead of a per-image estimate. Dispatch hangs off
 * {@link #getInstanceName()} ({@code "openrouter"}), so the protocol
 * type stays {@code openai} for chat and only the image path is
 * instance-routed.
 *
 * <p>Request shape (documented, OpenAPI
 * {@code post /images}): {@code model}, {@code prompt},
 * {@code aspect_ratio} (W:H string, taken verbatim from the
 * Fenchurch aspect ratio — no pixel mapping), {@code n: 1}
 * (Fenchurch generates one image per call), no {@code stream}.
 *
 * <p>Response shape: {@code data[0].b64_json} + {@code data[0].
 * media_type} (present whenever the format is identifiable) +
 * {@code usage.cost} (USD, may be absent when the provider cannot
 * price the call). Failures arrive as {@code {error:{message}}}
 * with an HTTP status; billing is all-or-nothing on OpenRouter's
 * side, so a failed call costs nothing.
 */
@Component
public class OpenRouterImageProvider implements AiImageModelProvider {

    /** Dispatch key — matches the bundled sidecar's instance name. */
    static final String INSTANCE_NAME = "openrouter";

    private static final String DEFAULT_BASE_URL = "https://openrouter.ai/api/v1";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final String defaultBaseUrl;

    public OpenRouterImageProvider(@Value("${vance.ai.openrouter.base-url:}") String baseUrl) {
        this.defaultBaseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl.trim();
    }

    @Override
    public ProviderType getType() {
        // The instance is declared with wireType: openai; this adapter
        // is selected via getInstanceName(), never via the protocol
        // type (that would steal OpenAI-proper calls).
        return ProviderType.OPENAI;
    }

    @Override
    public Optional<String> getInstanceName() {
        return Optional.of(INSTANCE_NAME);
    }

    @Override
    public void generate(AiImageConfig config, String prompt, ImageDestinationStream destination) {
        long start = System.currentTimeMillis();
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", config.modelName());
        body.put("prompt", prompt);
        body.put("aspect_ratio", config.aspectRatio());
        body.put("n", 1);
        executeAndCommit(config, body, start, destination);
    }

    @Override
    public void edit(
            AiImageConfig config,
            String prompt,
            java.util.List<de.mhus.vance.brain.ai.image.ImageReference> references,
            ImageDestinationStream destination) {
        if (references == null || references.isEmpty()) {
            throw new AiImageException(
                    "OpenRouter image edit requires at least one reference image for " + config.fullName());
        }
        long start = System.currentTimeMillis();
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", config.modelName());
        body.put("prompt", prompt);
        body.put("aspect_ratio", config.aspectRatio());
        body.put("n", 1);
        ArrayNode refs = body.putArray("input_references");
        for (de.mhus.vance.brain.ai.image.ImageReference ref : references) {
            ObjectNode part = refs.addObject();
            part.put("type", "image_url");
            part.putObject("image_url").put("url", ref.toDataUrl());
        }
        executeAndCommit(config, body, start, destination);
    }

    /**
     * Shared request/response/commit path — {@code generate} and
     * {@code edit} differ only in the body, everything after the POST
     * is identical (same route, same response shape, same commit).
     */
    private void executeAndCommit(
            AiImageConfig config, ObjectNode body, long start, ImageDestinationStream destination) {
        String baseUrl = config.baseUrl() != null ? config.baseUrl() : defaultBaseUrl;
        String imagesUrl = baseUrl.endsWith("/") ? baseUrl + "images" : baseUrl + "/images";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(imagesUrl))
                .header("Authorization", "Bearer " + config.apiKey())
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HttpResponse<String> response;
        try {
            response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build()
                    .send(request, HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            throw new AiImageException(
                    "OpenRouter image request failed for " + config.fullName() + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiImageException("OpenRouter image request interrupted for " + config.fullName(), e);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new AiImageException("OpenRouter image generation failed for " + config.fullName()
                    + ": HTTP " + response.statusCode()
                    + " — " + errorMessage(response.body()));
        }

        JsonNode root = parseJson(response.body(), config);
        long durationMs = System.currentTimeMillis() - start;
        writeToDestination(root, config, durationMs, destination);
    }

    private JsonNode parseJson(String body, AiImageConfig config) {
        try {
            return MAPPER.readTree(body);
        } catch (RuntimeException e) {
            throw new AiImageException("OpenRouter returned a non-JSON body for " + config.fullName(), e);
        }
    }

    static String errorMessage(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode message = root.path("error").path("message");
            if (!message.isMissingNode() && !message.asText().isBlank()) {
                return message.asText();
            }
        } catch (RuntimeException ignored) {
            // fall through — raw body is the fallback message
        }
        if (body == null) {
            return "(no response body)";
        }
        String trimmed = body.strip();
        return trimmed.length() > 300 ? trimmed.substring(0, 300) + "…" : trimmed;
    }

    static void writeToDestination(
            JsonNode root, AiImageConfig config, long durationMs, ImageDestinationStream destination) {
        JsonNode data = root.path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw new AiImageException("OpenRouter returned no image data for " + config.fullName());
        }
        JsonNode first = data.get(0);
        String b64 = first.path("b64_json").asText(null);
        if (b64 == null || b64.isBlank()) {
            throw new AiImageException("OpenRouter image response carries no base64 data for " + config.fullName()
                    + " (expected data[0].b64_json)");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            throw new AiImageException(
                    "Failed to decode OpenRouter base64 image data for " + config.fullName() + ": " + e.getMessage(),
                    e);
        }

        // media_type is present whenever the format is identifiable —
        // but not guaranteed, so sniff unknowns instead of assuming PNG.
        String mimeType =
                ImageMimeTypeSniffer.resolveOrSniff(first.path("media_type").asText(null), bytes, "image/png");

        destination.setMimeType(mimeType);
        destination.setMetadata("model", config.fullName());
        destination.setMetadata("durationMs", Long.toString(durationMs));
        destination.setMetadata("aspectRatio", config.aspectRatio());

        // The real, vendor-reported cost in USD — the whole point of
        // the dedicated provider: token- and megapixel-priced models
        // book their actual spend instead of a flat-rate estimate.
        @Nullable Double cost = usageCost(root);
        if (cost != null) {
            destination.setMetadata("costUsd", Double.toString(cost));
        }

        try {
            destination.write(bytes, 0, bytes.length);
        } catch (RuntimeException e) {
            throw new AiImageException(
                    "Failed to stream OpenRouter image into destination for " + config.fullName() + ": "
                            + e.getMessage(),
                    e);
        }
        destination.close();
    }

    /** {@code usage.cost} from the response, null when absent. */
    static @Nullable Double usageCost(JsonNode root) {
        JsonNode cost = root.path("usage").path("cost");
        if (cost.isMissingNode() || !cost.isNumber()) {
            return null;
        }
        return cost.asDouble();
    }
}
