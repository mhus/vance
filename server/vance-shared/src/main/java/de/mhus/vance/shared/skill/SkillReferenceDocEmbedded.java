package de.mhus.vance.shared.skill;

import de.mhus.vance.api.skills.SkillReferenceDocLoadMode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Mongo-embedded representation of a markdown reference document
 * carried by a skill.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillReferenceDocEmbedded {

    private String title = "";

    private String content = "";

    @Builder.Default
    private SkillReferenceDocLoadMode loadMode = SkillReferenceDocLoadMode.INLINE;
}
