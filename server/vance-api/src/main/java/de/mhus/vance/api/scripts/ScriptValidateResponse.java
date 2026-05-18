package de.mhus.vance.api.scripts;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Outcome of {@code POST /scripts/validate} — parse-only result.
 *
 * <p>{@code ok=true} means the source survives GraalJS's parser; semantic
 * issues (runtime errors, blocking loops) are not caught here — see
 * {@link ScriptDeepValidateResponse}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("scripts")
public class ScriptValidateResponse {

    private boolean ok;

    @Builder.Default
    private List<ScriptValidateError> errors = new ArrayList<>();
}
