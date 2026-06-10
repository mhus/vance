package de.mhus.vance.brain.ai.image.openai;

import de.mhus.vance.brain.ai.ProviderType;
import de.mhus.vance.brain.ai.image.AiImageConfig;
import de.mhus.vance.brain.ai.image.AiImageException;
import de.mhus.vance.brain.ai.image.AiImageModelProvider;
import de.mhus.vance.shared.document.ImageDestinationStream;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.model.openai.OpenAiImageModel;
import dev.langchain4j.model.output.Response;
import java.time.Duration;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * OpenAI image-generation provider.
 *
 * <p>Builds a {@link OpenAiImageModel} per call from {@link AiImageConfig},
 * runs {@code generate(prompt)}, and streams the resulting bytes into the
 * caller's {@link ImageDestinationStream}. Forces {@code responseFormat=b64_json}
 * so the response always carries inline bytes — no URL-fetch round-trip
 * (URLs are short-lived on OpenAI's CDN; for a single-call workflow
 * grabbing base64 is simpler and one fewer thing that can fail).
 *
 * <p>Aspect-ratio mapping (OpenAI takes a literal pixel size, not a
 * W:H string):
 * <ul>
 *   <li>{@code 1:1}        → {@code 1024x1024}</li>
 *   <li>{@code 16:9} / {@code 4:3} → {@code 1536x1024} (landscape)</li>
 *   <li>{@code 9:16} / {@code 3:4} → {@code 1024x1536} (portrait)</li>
 *   <li>anything else      → {@code auto} (model picks)</li>
 * </ul>
 *
 * <p>Quality tier: v1 always asks for {@code "high"}. The high-quality
 * default vs. fast default is decided at the alias level
 * ({@code default:image-high} → this provider, {@code default:image}
 * → Gemini nano-banana), not via a tool parameter. If we ever want
 * to spend less per call on this provider, the quality string moves
 * to {@link AiImageConfig}.
 */
@Component
@Slf4j
public class OpenAiImageProvider implements AiImageModelProvider {

    private final String defaultBaseUrl;

    public OpenAiImageProvider(
            @Value("${vance.ai.openai.base-url:https://api.openai.com/v1}") String baseUrl) {
        this.defaultBaseUrl = baseUrl;
    }

    @Override
    public ProviderType getType() {
        return ProviderType.OPENAI;
    }

    @Override
    public void generate(AiImageConfig config, String prompt,
                         ImageDestinationStream destination) {
        long start = System.currentTimeMillis();
        OpenAiImageModel model = buildModel(config);
        Response<Image> response;
        try {
            response = model.generate(prompt);
        } catch (RuntimeException e) {
            throw new AiImageException(
                    "OpenAI image generation failed for " + config.fullName()
                            + ": " + e.getMessage(), e);
        }
        long durationMs = System.currentTimeMillis() - start;
        writeToDestination(response.content(), config, durationMs, destination);
    }

    private OpenAiImageModel buildModel(AiImageConfig config) {
        String baseUrl = config.baseUrl() != null ? config.baseUrl() : defaultBaseUrl;
        return OpenAiImageModel.builder()
                .baseUrl(baseUrl)
                .apiKey(config.apiKey())
                .modelName(config.modelName())
                .size(mapAspectRatioToSize(config.aspectRatio()))
                .quality("high")
                .responseFormat("b64_json")
                .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                .maxRetries(0)   // Fenchurch handles retries at a higher level.
                .build();
    }

    static String mapAspectRatioToSize(String aspectRatio) {
        return switch (aspectRatio) {
            case "1:1" -> "1024x1024";
            case "16:9", "4:3" -> "1536x1024";
            case "9:16", "3:4" -> "1024x1536";
            default -> "auto";
        };
    }

    static void writeToDestination(
            Image image, AiImageConfig config, long durationMs,
            ImageDestinationStream destination) {
        if (image == null) {
            throw new AiImageException(
                    "OpenAI returned no image for " + config.fullName());
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
                    "Failed to stream OpenAI image into destination for "
                            + config.fullName() + ": " + e.getMessage(), e);
        }
        destination.close();
    }

    static byte[] decodeBytes(Image image, AiImageConfig config) {
        String b64 = image.base64Data();
        if (b64 == null || b64.isBlank()) {
            throw new AiImageException(
                    "OpenAI image response carries no base64 data for "
                            + config.fullName()
                            + " (responseFormat=b64_json was requested)");
        }
        try {
            return Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            throw new AiImageException(
                    "Failed to decode OpenAI base64 image data for "
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
