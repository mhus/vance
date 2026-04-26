package de.mhus.vance.api.thinkprocess;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Specifies a think-process to create as part of a
 * {@code session-bootstrap} request.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("thinkprocess")
public class ProcessSpec {

    @NotBlank
    private String engine;

    @NotBlank
    private String name;

    private @Nullable String title;

    private @Nullable String goal;

    /**
     * Engine-specific runtime parameters. Schema is engine-defined —
     * see e.g. {@code ArthurEngine}'s recognised keys
     * ({@code model}, {@code maxIterations}, {@code validation}).
     * {@code null} or absent → engine defaults.
     */
    private @Nullable Map<String, Object> params;
}
