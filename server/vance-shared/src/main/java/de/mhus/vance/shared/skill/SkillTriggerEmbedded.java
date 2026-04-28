package de.mhus.vance.shared.skill;

import de.mhus.vance.api.skills.SkillTriggerType;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Mongo-embedded representation of a skill auto-trigger. Mirrors
 * {@code SkillTriggerDto} from vance-api but lives on the persistence
 * side so the API layer can evolve independently.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillTriggerEmbedded {

    private SkillTriggerType type = SkillTriggerType.PATTERN;

    private @Nullable String pattern;

    @Builder.Default
    private List<String> keywords = new ArrayList<>();
}
