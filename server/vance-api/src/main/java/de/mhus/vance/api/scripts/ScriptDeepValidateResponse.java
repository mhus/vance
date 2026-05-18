package de.mhus.vance.api.scripts;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * LLM-backed review result. {@code warnings} carries categorised
 * findings (infinite-loop suspects, blocking I/O, missing returns,
 * header anomalies) the model flagged. Empty list when the model
 * found nothing to complain about.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("scripts")
public class ScriptDeepValidateResponse {

    @Builder.Default
    private List<ScriptDeepWarning> warnings = new ArrayList<>();

    /** Wall-clock ms when the LLM produced this review. */
    private long reviewedAtMs;

    /** Free-form summary from the model, if any. */
    private @Nullable String summary;
}
