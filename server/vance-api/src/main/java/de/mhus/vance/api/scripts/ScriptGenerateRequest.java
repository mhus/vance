package de.mhus.vance.api.scripts;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /brain/{tenant}/scripts/generate?projectId=…&sessionId=…} —
 * spawns a Deep-Thought process that produces a JavaScript orchestrator
 * script for the given prompt. When {@code existingScriptId} is set the
 * current content of that script is included in the prompt context, so
 * Deep-Thought rewrites instead of generating from scratch.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("scripts")
public class ScriptGenerateRequest {

    @NotBlank
    private String prompt;

    private @Nullable String existingScriptId;
}
