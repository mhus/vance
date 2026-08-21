package de.mhus.vance.api.milliways;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * What is being shared. Four attributes, all optional, additive — a document,
 * a link, a quoted snippet, and a title labelling whichever of them is there.
 *
 * <p>At least one of {@link #link} / {@link #snippet} / {@link #documentPath}
 * must be present: {@link #title} is the <em>label</em> of the thing, not the
 * thing. Without that rule a title plus a reason would be a valid share, and
 * Milliways would have become a note sender by degeneration rather than by
 * decision.
 *
 * <p>The caller supplies this — Cortex a document, the search app a title,
 * link and snippet. It is deliberately not part of the handler's form: the
 * subject is not the handler's business, and asking for it per handler would
 * ask twice, possibly differently.
 *
 * <p>{@link #documentPath} is a path inside the project named by the request.
 * A cross-project subject would add a field rather than change this one.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("milliways")
public class ShareSubjectDto {

    /** Label. Falls back to the document title / file name / link host. */
    private @Nullable String title;

    /** Absolute {@code http}/{@code https}/{@code mailto} URL. */
    private @Nullable String link;

    /** Quoted foreign text — rendered as a quote, never as Markdown. */
    private @Nullable String snippet;

    /** Document path within the request's project. */
    private @Nullable String documentPath;
}
