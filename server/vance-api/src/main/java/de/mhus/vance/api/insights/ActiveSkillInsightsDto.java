package de.mhus.vance.api.insights;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Read-only view of a skill currently active on a process.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("insights")
public class ActiveSkillInsightsDto {

    private String name;

    /** Origin scope — {@code USER} / {@code PROJECT} / {@code TENANT} / {@code BUNDLED}. */
    private @Nullable String resolvedFromScope;

    private boolean oneShot;

    private boolean fromRecipe;

    private @Nullable Instant activatedAt;
}
