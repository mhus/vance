package de.mhus.vance.brain.ai.iris;

import de.mhus.vance.brain.ai.ProviderType;
import de.mhus.vance.brain.ai.image.AiImageException;
import de.mhus.vance.brain.ai.image.openrouter.OpenRouterImageProvider;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Image generation through Iris. Iris serves the OpenRouter image wire
 * ({@code POST /v1/images}), so this is the OpenRouter adapter, selected by the
 * {@code iris} protocol instead of by an instance name — any instance whose type
 * is {@code iris} gets it.
 *
 * <p>Two differences matter. There is no fallback base URL (an Iris key sent to
 * OpenRouter would leak it), and the vendor's {@code usage.cost} is not reported:
 * it is what the provider charged Iris, in USD, not what the tenant pays Iris.
 * The price comes from the catalog, which discovery fills from Iris' own prices.
 */
@Component
public class IrisImageProvider extends OpenRouterImageProvider {

    public IrisImageProvider() {
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
            throw new AiImageException("The iris image provider needs ai.provider.<instance>.baseUrl");
        }
        return configured;
    }

    @Override
    protected boolean reportsVendorCost() {
        return false;
    }
}
