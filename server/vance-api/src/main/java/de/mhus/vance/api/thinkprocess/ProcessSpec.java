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

    /**
     * Engine name from the registry (e.g. {@code "zaphod"}). One of
     * {@code engine} or {@link #recipe} must be set; if both are set,
     * {@code recipe} wins and {@code engine} is treated as advisory.
     */
    private @Nullable String engine;

    /**
     * Recipe name for resolution via the recipe cascade. Preferred
     * over {@link #engine} — Arthur's default delegation path.
     */
    private @Nullable String recipe;

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
