package de.mhus.vance.api.scripts;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One LLM-flagged risk. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("scripts")
public class ScriptDeepWarning {

    /** Severity bucket: {@code info}, {@code warn}, {@code error}. */
    private String severity;

    /** Short category label: {@code infinite-loop}, {@code blocking-io},
     *  {@code missing-return}, {@code header}, {@code style}, ... */
    private String category;

    /** Human-readable message. */
    private String message;

    /** 1-based line in the source the warning refers to, or 0 if N/A. */
    private int line;
}
