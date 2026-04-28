package de.mhus.vance.api.skills;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Auto-trigger configuration. Either {@link #pattern} (for
 * {@link SkillTriggerType#PATTERN}) or {@link #keywords} (for
 * {@link SkillTriggerType#KEYWORDS}) is populated, depending on
 * {@link #type}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("skills")
public class SkillTriggerDto {

    @NotNull
    private SkillTriggerType type;

    /** Java-Regex string. Populated for {@link SkillTriggerType#PATTERN}. */
    private @Nullable String pattern;

    /** Keywords list. Populated for {@link SkillTriggerType#KEYWORDS}. */
    @Builder.Default
    private List<String> keywords = new ArrayList<>();
}
