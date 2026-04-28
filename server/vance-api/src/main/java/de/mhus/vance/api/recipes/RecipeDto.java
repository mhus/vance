package de.mhus.vance.api.recipes;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import de.mhus.vance.api.thinkprocess.PromptMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Read view of a recipe — used both by single-record GETs and by the
 * effective-recipes list. {@link #source} indicates where this copy
 * lives in the cascade so the UI can mark inherited vs. owned.
 *
 * <p>Bundled recipes never carry a {@link #projectId}; tenant copies
 * have a {@code null} {@link #projectId}; project copies set both.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("recipes")
public class RecipeDto {

    private String name;

    private String description;

    private String engine;

    @Builder.Default
    private Map<String, Object> params = new LinkedHashMap<>();

    private @Nullable String promptPrefix;

    private @Nullable String promptPrefixSmall;

    private PromptMode promptMode;

    private @Nullable String intentCorrection;

    private @Nullable String dataRelayCorrection;

    @Builder.Default
    private List<String> allowedToolsAdd = new ArrayList<>();

    @Builder.Default
    private List<String> allowedToolsRemove = new ArrayList<>();

    private boolean locked;

    @Builder.Default
    private List<String> tags = new ArrayList<>();

    private RecipeSource source;

    /** Set only when {@link #source} is {@link RecipeSource#PROJECT}. */
    private @Nullable String projectId;
}
