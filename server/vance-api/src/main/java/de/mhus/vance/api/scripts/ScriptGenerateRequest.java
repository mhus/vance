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
 * spawns a Slartibartfast process that produces a JavaScript
 * orchestrator script for the given prompt. The backend maps this
 * request to {@link de.mhus.vance.api.slartibartfast.OutputSchemaType#SCRIPT_JS}
 * with the appropriate {@link de.mhus.vance.api.slartibartfast.ArchitectMode}.
 *
 * <p>Two operation modes:
 * <ul>
 *   <li>{@code mode=CREATE} (default) — generate from scratch using
 *       {@link #prompt} as the user description.</li>
 *   <li>{@code mode=UPDATE} — modify an existing script. Requires
 *       {@link #existingScriptId} (the document being updated); the
 *       loaded body becomes context for the LLM. Optional
 *       {@link #failureReason} carries a Hactar
 *       {@code TerminationRationale.failureReason} from a prior
 *       FAILED run so the LLM knows what to fix.</li>
 * </ul>
 *
 * <p>Legacy callers may omit {@code mode} entirely — when
 * {@code existingScriptId} is set the backend defaults to UPDATE,
 * else CREATE. See {@code planning/script-architect-executor-split.md} §5.5.
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

    /** Document ID of the script being updated. Required for
     *  {@code mode=UPDATE}; ignored (but tolerated) for CREATE. The
     *  backend resolves the ID to the document path and passes it
     *  to Slart as {@code existingScriptRef}. */
    private @Nullable String existingScriptId;

    /** Operation mode — {@code "CREATE"} or {@code "UPDATE"}. Case-
     *  insensitive. {@code null} means "infer from existingScriptId"
     *  for backwards compatibility with pre-Phase-5 callers. */
    private @Nullable String mode;

    /** Optional failure reason from a prior Hactar FAILED run.
     *  When present + {@code mode=UPDATE}, surfaces in the LLM's
     *  PROPOSING prompt as the "what went wrong last time" context.
     *  Ignored when {@code mode=CREATE}. */
    private @Nullable String failureReason;
}
