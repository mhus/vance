package de.mhus.vance.api.magrathea;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Wire-shape for one entry under a workflow's {@code parameters:}
 * block. Mirrors the server-side {@code MagratheaParameterSpec} but lives
 * in {@code vance-api} so the Web-UI can build a parameter form for
 * manual triggers without pulling shared types.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("magrathea")
public class MagratheaParameterDto {

    /** {@code string} / {@code integer} / {@code boolean} / {@code object} / {@code array}. */
    private String type;

    private boolean required;

    /** Value substituted when the caller omits a non-required param. */
    private @Nullable Object defaultValue;
}
