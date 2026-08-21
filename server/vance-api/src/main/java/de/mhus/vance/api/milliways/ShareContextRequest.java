package de.mhus.vance.api.milliways;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body for the two read operations — listing the ways to share, and fetching
 * one way's form. Both need the subject: availability and the form's defaults
 * depend on what is being shared.
 *
 * <p>A body rather than query parameters, and therefore {@code POST} for a
 * read: a link can be 2000 characters and a snippet more. Two paths for the
 * same thing would be worse than one unfashionable verb.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@GenerateTypeScript("milliways")
public class ShareContextRequest {

    /** The project the sharer is acting in. */
    @NotBlank
    private String projectId;

    @NotNull
    @Valid
    private ShareSubjectDto subject;
}
