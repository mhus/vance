package de.mhus.vance.api.runs;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A way out of the run view — to the definition it runs, to the session
 * it lives in. The run view never renders those itself; it points at the
 * editor that owns them.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("runs")
public class RunLinkDto {

    /** i18n-free label; the client maps known {@link #rel} values itself. */
    private String label;

    /** {@code definition} | {@code session} | {@code document}. */
    private String rel;

    /** Target — a document path or a session id, interpreted per {@link #rel}. */
    private String target;
}
