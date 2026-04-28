package de.mhus.vance.api.skills;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A markdown reference document carried by a skill. Multiple docs per
 * skill are allowed.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("skills")
public class SkillReferenceDocDto {

    @NotBlank
    private String title;

    @NotBlank
    private String content;

    @NotNull
    @Builder.Default
    private SkillReferenceDocLoadMode loadMode = SkillReferenceDocLoadMode.INLINE;
}
