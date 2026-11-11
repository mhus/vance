package de.mhus.vance.brain.ai.iris;

import de.mhus.vance.brain.ai.ProviderType;
import de.mhus.vance.brain.ai.audio.AiAudioException;
import de.mhus.vance.brain.ai.audio.openrouter.OpenRouterAudioProvider;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Speech, transcription, music and audio analysis through Iris, which serves the
 * OpenRouter wire for them. It is the OpenRouter adapter, selected by the
 * {@code iris} protocol; see {@link IrisImageProvider} for the two differences
 * (no base-URL fallback, no vendor cost).
 */
@Component
public class IrisAudioProvider extends OpenRouterAudioProvider {

    public IrisAudioProvider() {
        super("");
    }

    @Override
    public ProviderType getType() {
        return ProviderType.IRIS;
    }

    @Override
    public Optional<String> getInstanceName() {
        return Optional.empty();
    }

    @Override
    protected String baseUrlFor(@Nullable String configured) {
        if (configured == null || configured.isBlank()) {
            throw new AiAudioException("The iris audio provider needs ai.provider.<instance>.baseUrl");
        }
        return configured;
    }

    @Override
    protected @Nullable Double vendorCost(JsonNode usage) {
        return null;
    }
}
