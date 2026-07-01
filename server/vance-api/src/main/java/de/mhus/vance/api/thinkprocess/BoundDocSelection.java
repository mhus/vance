package de.mhus.vance.api.thinkprocess;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Per-message character range the user has selected inside the
 * chat-bound Cortex document at send time ({@code from}..{@code to},
 * 0-based character offsets into the raw document text).
 *
 * <p>Rides with each {@code process-steer} request alongside
 * {@link ProcessSteerRequest#boundDocumentId}. Only the <em>range</em>
 * travels — never the selected text: the model reads the content on
 * demand via the server-side {@code doc_get_selection} tool, so a huge
 * selection never bloats the prompt. The prompt just tells the model a
 * selection exists and where.
 *
 * <p>Per-message, not session state; {@code null} when nothing is
 * selected.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("thinkprocess")
public class BoundDocSelection {

    /** Selection start — 0-based character offset into the document. */
    private int from;

    /** Selection end — 0-based character offset (exclusive). */
    private int to;
}
