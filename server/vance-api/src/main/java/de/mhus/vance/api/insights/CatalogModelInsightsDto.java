package de.mhus.vance.api.insights;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import de.mhus.vance.api.chatmodel.ChatModelPricingDto;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One model of the project's merged model catalog, every modality, with
 * the verdicts that decide whether it can be used there. Unlike the model
 * picker's list nothing is filtered out — the point is to see <em>why</em>
 * a model is missing from the picker.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("insights")
public class CatalogModelInsightsDto {

    /** {@code providerInstance:modelName}. */
    private String spec;

    private String providerInstance;

    private String modelName;

    /** {@code chat} | {@code image} | {@code tts} | {@code stt} | {@code music} | {@code au} | {@code decision}. */
    private String kind;

    /** {@code SMALL} | {@code LARGE}; chat models only. */
    private @Nullable String size;

    /** Chat models only. */
    private @Nullable Integer contextWindow;

    /** Chat models only. */
    private @Nullable Integer maxOutputTokens;

    /** Lower-case capability names ({@code vision}, {@code thinking}, …); chat models only. */
    private List<String> capabilities;

    /** Token list price; chat models with a catalog price only. */
    private @Nullable ChatModelPricingDto pricing;

    /** Innermost catalog layer that contributed a field. */
    private ModelCatalogLayer origin;

    /** Whether any field comes from a discovery-written layer. */
    private boolean discovered;

    /**
     * The provider instance has credentials (or, keyless, a base URL) in
     * this project, so a chat could be built on it; {@code null} for
     * non-chat models, whose services read their own settings.
     */
    private @Nullable Boolean instanceConfigured;

    /** Approved by {@code chat.allowed-models}; {@code null} for non-chat models. */
    private @Nullable Boolean chatAllowed;
}
