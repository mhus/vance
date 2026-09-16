package de.mhus.vance.api.skills;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Read-side listing view of a reference document carried by a skill.
 * Multiple docs per skill are allowed. Carries the metadata a picker
 * needs (title, teaser, load mode) — the content itself lives in the
 * document editor and in the turn prompt, not in this listing.
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

    /**
     * One-line teaser shown behind the title when present. Used with
     * {@link SkillReferenceDocLoadMode#ON_DEMAND} so the listing says
     * what {@code manual_read} would fetch.
     */
    private @Nullable String summary;

    @NotNull
    @Builder.Default
    private SkillReferenceDocLoadMode loadMode = SkillReferenceDocLoadMode.INLINE;
}
