package de.mhus.vance.api.skills;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Read view of a skill — used both by single-record GETs and by the
 * effective-skills list. {@link #scope} indicates where this copy
 * lives in the cascade so the UI can mark inherited vs. owned.
 *
 * <p>Bundled skills never carry {@link #projectId} or {@link #userId};
 * tenant copies set neither; project copies set {@link #projectId};
 * user copies set {@link #userId}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("skills")
public class SkillDto {

    private String name;

    private String title;

    private String description;

    private String version;

    @Builder.Default
    private List<SkillTriggerDto> triggers = new ArrayList<>();

    private @Nullable String promptExtension;

    @Builder.Default
    private List<String> tools = new ArrayList<>();

    @Builder.Default
    private List<SkillReferenceDocDto> referenceDocs = new ArrayList<>();

    @Builder.Default
    private List<String> tags = new ArrayList<>();

    @Builder.Default
    private boolean enabled = true;

    private SkillScope scope;

    /** Set only when {@link #scope} is {@link SkillScope#PROJECT}. */
    private @Nullable String projectId;

    /** Set only when {@link #scope} is {@link SkillScope#USER}. */
    private @Nullable String userId;
}
