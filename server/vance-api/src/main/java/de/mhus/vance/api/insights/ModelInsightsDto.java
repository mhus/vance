package de.mhus.vance.api.insights;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/** The Insights "Models" tab: the project's merged model catalog, its aliases and its default. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("insights")
public class ModelInsightsDto {

    /** Sorted by provider instance, then model name. */
    private List<CatalogModelInsightsDto> models;

    /** Sorted by alias. */
    private List<ModelAliasInsightsDto> aliases;

    /** Resolved {@code ai.default.provider}/{@code ai.default.model}; {@code null} when unset or broken. */
    private @Nullable String defaultModel;

    /** Why {@link #defaultModel} is missing. */
    private @Nullable String defaultModelError;

    /** Effective {@code chat.allowed-models} ({@code *} when unset). */
    private String chatAllowlist;
}
