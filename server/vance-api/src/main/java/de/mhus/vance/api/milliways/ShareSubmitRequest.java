package de.mhus.vance.api.milliways;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
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
 * that interprets it. Nothing here is trusted: the service re-checks that
 * the document exists and is readable by the sharer, and the handler
 * re-validates its own required fields.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@GenerateTypeScript("milliways")
public class ShareSubmitRequest {

    @NotBlank
    private String projectId;

    /** Document path inside the project. */
    @NotBlank
    private String path;

    @Builder.Default
    private Map<String, Object> values = new LinkedHashMap<>();
}
