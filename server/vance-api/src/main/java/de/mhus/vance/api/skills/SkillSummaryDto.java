package de.mhus.vance.api.skills;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Lightweight read-only view of a skill for chat / picker UIs. Carries
 * what the user needs to recognise and pick a skill — name, title,
 * description, tags, and the cascade source. The full editing payload
 * (triggers, prompt-extension, reference docs) is not part of this
 * surface; editors go through the document layer directly.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("skills")
public class SkillSummaryDto {

    private String name;

    private String title;

    private String description;

    private String version;

    @Builder.Default
    private List<String> tags = new ArrayList<>();

    private boolean enabled;

    /** Cascade tier that produced this skill. */
    private SkillScope source;
}
