package de.mhus.vance.brain.ai.image.gemini;

import de.mhus.vance.brain.ai.ProviderType;
import de.mhus.vance.brain.ai.image.AiImageConfig;
import de.mhus.vance.brain.ai.image.AiImageException;
import de.mhus.vance.brain.ai.image.AiImageModelProvider;
import de.mhus.vance.shared.document.ImageDestinationStream;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.model.googleai.GoogleAiGeminiImageModel;
import dev.langchain4j.model.output.Response;
import java.time.Duration;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Google AI Gemini image-generation provider.
 *
 * <p>Builds a {@link GoogleAiGeminiImageModel} per call. Gemini takes
 * aspect-ratio natively (no W:H → pixel-size mapping needed) and always
 * returns inline base64 — no URL round-trip. Covers both
 * {@code gemini-2.5-flash-image} (nano-banana, the fast/cheap
 * default for {@code default:image}) and {@code imagen-3.0-generate-002}
 * (higher quality, slower).
 */
@Component
@Slf4j
public class GeminiImageProvider implements AiImageModelProvider {

    private final String defaultBaseUrl;

    public GeminiImageProvider(
            @Value("${vance.ai.gemini.base-url:}") String baseUrl) {
        this.defaultBaseUrl = baseUrl == null || baseUrl.isBlank() ? null : baseUrl;
    }

    @Override
    public ProviderType getType() {
        return ProviderType.GEMINI;
    }

    @Override
    public void generate(AiImageConfig config, String prompt,
                         ImageDestinationStream destination) {
        long start = System.currentTimeMillis();
        GoogleAiGeminiImageModel model = buildModel(config);
        Response<Image> response;
        try {
            response = model.generate(prompt);
        } catch (RuntimeException e) {
            throw new AiImageException(
                    "Gemini image generation failed for " + config.fullName()
                            + ": " + e.getMessage(), e);
        }
        long durationMs = System.currentTimeMillis() - start;
        writeToDestination(response.content(), config, durationMs, destination);
    }

    private GoogleAiGeminiImageModel buildModel(AiImageConfig config) {
        GoogleAiGeminiImageModel.GoogleAiGeminiImageModelBuilder builder =
                GoogleAiGeminiImageModel.builder()
                        .apiKey(config.apiKey())
                        .modelName(config.modelName())
                        .aspectRatio(config.aspectRatio())
                        .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                        .maxRetries(0);   // Fenchurch handles retries above.
        if (config.baseUrl() != null) {
            builder.baseUrl(config.baseUrl());
        } else if (defaultBaseUrl != null) {
            builder.baseUrl(defaultBaseUrl);
        }
        return builder.build();
    }

    static void writeToDestination(
            Image image, AiImageConfig config, long durationMs,
            ImageDestinationStream destination) {
        if (image == null) {
            throw new AiImageException(
                    "Gemini returned no image for " + config.fullName());
        }
        byte[] bytes = decodeBytes(image, config);
        String mimeType = resolveMimeType(image.mimeType());

        destination.setMimeType(mimeType);
        destination.setMetadata("model", config.fullName());
        destination.setMetadata("durationMs", Long.toString(durationMs));
        destination.setMetadata("aspectRatio", config.aspectRatio());
        @Nullable String revised = image.revisedPrompt();
        if (revised != null && !revised.isBlank()) {
            destination.setMetadata("revisedPrompt", revised);
        }
        try {
            destination.write(bytes, 0, bytes.length);
        } catch (RuntimeException e) {
            throw new AiImageException(
                    "Failed to stream Gemini image into destination for "
                            + config.fullName() + ": " + e.getMessage(), e);
        }
        destination.close();
    }

    static byte[] decodeBytes(Image image, AiImageConfig config) {
        String b64 = image.base64Data();
        if (b64 == null || b64.isBlank()) {
            throw new AiImageException(
                    "Gemini image response carries no base64 data for "
                            + config.fullName());
        }
        try {
            return Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            throw new AiImageException(
                    "Failed to decode Gemini base64 image data for "
                            + config.fullName() + ": " + e.getMessage(), e);
        }
    }

    static String resolveMimeType(@Nullable String reported) {
        if (reported == null || reported.isBlank()) {
            return "image/png";
        }
        return reported;
    }
}
