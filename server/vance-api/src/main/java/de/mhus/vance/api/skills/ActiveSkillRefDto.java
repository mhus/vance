package de.mhus.vance.api.skills;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Lightweight reference describing a skill that is currently active on
 * a {@code ThinkProcessDocument}. Persisted on the process document and
 * also pushed to clients as part of the chat-state envelope so the UI
 * can render "Skill: code-review aktiv" badges.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("skills")
public class ActiveSkillRefDto {

    private String name;

    private SkillScope resolvedFromScope;

    private boolean oneShot;

    private Instant activatedAt;
}
