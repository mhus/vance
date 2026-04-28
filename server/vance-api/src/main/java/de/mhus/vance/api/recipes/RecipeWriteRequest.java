package de.mhus.vance.api.recipes;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import de.mhus.vance.api.thinkprocess.PromptMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
 * Body of {@code PUT /brain/{tenant}/admin/recipes/{name}} (and the
 * project-scoped variant). The recipe's {@code name} is taken from the
 * URL path; everything else is in the body. Upsert semantics: a missing
 * record is created, an existing one is replaced wholesale.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("recipes")
public class RecipeWriteRequest {

    @NotBlank
    private String description;

    @NotBlank
    private String engine;

    @Builder.Default
    private Map<String, Object> params = new LinkedHashMap<>();

    private @Nullable String promptPrefix;

    private @Nullable String promptPrefixSmall;

    @NotNull
    @Builder.Default
    private PromptMode promptMode = PromptMode.APPEND;

    private @Nullable String intentCorrection;

    private @Nullable String dataRelayCorrection;

    @Builder.Default
    private List<String> allowedToolsAdd = new ArrayList<>();

    @Builder.Default
    private List<String> allowedToolsRemove = new ArrayList<>();

    private boolean locked;

    @Builder.Default
    private List<String> tags = new ArrayList<>();
}
