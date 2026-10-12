package de.mhus.vance.api.skills;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Reply to a {@link SkillModeRequest}: the now-effective activation mode
 * with its provenance, so the caller can confirm what the next turn will
 * see (mode changes take effect at the next compose, §3.4 — never
 * mid-turn).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("skills")
public class SkillModeResponse {

    /** The skill the mode was set for. */
    private String skillName;

    /** The now-effective activation mode. */
    private SkillActivation activation;

    /** Provenance of {@link #activation} — an override tier after this write. */
    private SkillActivationSource activationSource;

    /** The override tier that now carries the mode. */
    private @Nullable SkillScope activationOverrideScope;
}
