package de.mhus.vance.api.skills;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code PUT /brain/{tenant}/admin/skills/{name}} (and the
 * project- and user-scoped variants). The skill's {@code name} is taken
 * from the URL path; everything else is in the body. Upsert semantics:
 * a missing record is created, an existing one is replaced wholesale.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("skills")
public class SkillWriteRequest {

    @NotBlank
    private String title;

    @NotBlank
    private String description;

    @NotBlank
    private String version;

    @Valid
    @Builder.Default
    private List<SkillTriggerDto> triggers = new ArrayList<>();

    private @Nullable String promptExtension;

    @Builder.Default
    private List<String> tools = new ArrayList<>();

    @Valid
    @Builder.Default
    private List<SkillReferenceDocDto> referenceDocs = new ArrayList<>();

    @Builder.Default
    private List<String> tags = new ArrayList<>();

    @Builder.Default
    private boolean enabled = true;
}
