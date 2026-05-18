package de.mhus.vance.api.scripts;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /brain/{tenant}/scripts/validate} and
 * {@code /scripts/validate-deep}. Either {@code scriptId} or {@code code}
 * must be set: with {@code scriptId} the server loads the current inline
 * content of the document, otherwise {@code code} is validated as-is.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("scripts")
public class ScriptValidateRequest {

    private @Nullable String scriptId;
    private @Nullable String code;
    private @Nullable String sourceName;
}
