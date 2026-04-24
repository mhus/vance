package de.mhus.vance.api.thinkprocess;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code process-create} — client spawns a think-process in its
 * currently bound session.
 *
 * <p>The {@code engine} is the registry name (e.g. {@code "zaphod"}).
 * {@code name} is the process's identifier within the session; it must be
 * unique per session.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("thinkprocess")
public class ProcessCreateRequest {

    @NotBlank
    private String engine;

    @NotBlank
    private String name;

    private @Nullable String title;

    private @Nullable String goal;
}
