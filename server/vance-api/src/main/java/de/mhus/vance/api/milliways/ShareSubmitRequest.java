package de.mhus.vance.api.milliways;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body of {@code POST /brain/{tenant}/share/handlers/{id}} — what to share
 * plus the filled-in form.
 *
 * <p>{@link #values} is the raw submission keyed by
 * {@code FormFieldDto.name}; the handler that declared the form is the one
 * that interprets it. Nothing here is trusted: the service re-checks the
 * subject (document readable, link scheme allowed, snippet length) and the
 * handler re-validates its own required fields.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@GenerateTypeScript("milliways")
public class ShareSubmitRequest {

    /** The project the sharer is acting in. */
    @NotBlank
    private String projectId;

    @NotNull
    @Valid
    private ShareSubjectDto subject;

    @Builder.Default
    private Map<String, Object> values = new LinkedHashMap<>();
}
