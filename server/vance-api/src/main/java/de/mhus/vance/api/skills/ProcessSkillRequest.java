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
 * WebSocket {@code process-skill} request payload. Sent by clients to
 * mutate the active-skill list of a think-process or to read it back.
 *
 * <p>Field requirements per command:
 * <ul>
 *   <li>{@code ACTIVATE} — requires {@link #processName} and
 *       {@link #skillName}. {@link #oneShot} optional (default false).</li>
 *   <li>{@code CLEAR} — requires {@link #processName} and
 *       {@link #skillName}.</li>
 *   <li>{@code CLEAR_ALL} — requires {@link #processName}.</li>
 *   <li>{@code LIST} — requires {@link #processName}.</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("skills")
public class ProcessSkillRequest {

    @NotBlank
    private String processName;

    @NotNull
    private ProcessSkillCommand command;

    /** Required for {@code ACTIVATE} and {@code CLEAR}. */
    private @Nullable String skillName;

    /** Drains after the next lane-turn. Only meaningful for {@code ACTIVATE}. */
    private boolean oneShot;
}
