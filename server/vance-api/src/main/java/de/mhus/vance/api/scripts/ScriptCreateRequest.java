package de.mhus.vance.api.scripts;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /brain/{tenant}/scripts?projectId=…} — creates a new
 * Script-Cortex document. {@code kind="script"} is stamped server-side, the
 * mime-type is derived from the path extension when absent.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("scripts")
public class ScriptCreateRequest {

    /** Virtual path inside the project, e.g. {@code "utils/sum.js"}. */
    @NotBlank
    private String path;

    private @Nullable String title;

    private @Nullable List<String> tags;

    /** Optional override; otherwise derived from path extension
     *  ({@code .js → text/javascript}, {@code .json → application/json},
     *  {@code .md → text/markdown}, else {@code text/plain}). */
    private @Nullable String mimeType;

    /** Initial file content. May be empty. */
    @Nullable
    private String inlineText;
}
