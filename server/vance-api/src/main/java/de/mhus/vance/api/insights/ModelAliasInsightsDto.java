package de.mhus.vance.api.insights;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/** One {@code ai.alias.*} setting as the project sees it, and what it resolves to there. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("insights")
public class ModelAliasInsightsDto {

    /** Alias spec, e.g. {@code default:chat}. */
    private String alias;

    /** Raw setting value — a spec or a comma cascade of specs. */
    private String target;

    /** The settings scope the value comes from. */
    private ModelAliasScope definedIn;

    /** Resolved {@code providerInstance:modelName}; {@code null} when resolution fails. */
    private @Nullable String resolved;

    /** Alias specs expanded on the way to {@link #resolved}, starting with {@link #alias}. */
    private List<String> chain;

    /** An undefined {@code default:} alias fell back to the tenant default model. */
    private boolean viaTenantDefault;

    /** Why resolution failed. */
    private @Nullable String error;
}
