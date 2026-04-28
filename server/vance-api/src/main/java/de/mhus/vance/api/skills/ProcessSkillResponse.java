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
 * Response to a {@link ProcessSkillRequest}. Always carries the
 * post-mutation snapshot of {@link #activeSkills} so the client UI can
 * refresh its badge without a follow-up roundtrip.
 *
 * <p>{@link #availableSkills} is populated only for
 * {@link ProcessSkillCommand#LIST} — the union of skills visible in
 * the current process's scope (cascade-deduped, see
 * {@code SkillResolver.listAvailable}).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("skills")
public class ProcessSkillResponse {

    private String processName;

    @Builder.Default
    private List<ActiveSkillRefDto> activeSkills = new ArrayList<>();

    /** Populated for {@link ProcessSkillCommand#LIST} responses. */
    @Builder.Default
    private List<SkillDto> availableSkills = new ArrayList<>();
}
