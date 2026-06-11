package de.mhus.vance.api.python;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /brain/{tenant}/python/execute?projectId=…}.
 *
 * <p>Either {@code scriptId} or {@code code} must be set. With
 * {@code scriptId} the server loads the document body and runs it;
 * otherwise {@code code} is the literal source. {@code sourceName}
 * is used as the script's display name in log lines / exec records.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("python")
public class PythonExecuteRequest {

    private @Nullable String scriptId;
    private @Nullable String code;
    private @Nullable String sourceName;

    /** Shell-style args passed after the script path. Each item is
     *  shell-escaped server-side. */
    @Builder.Default
    private List<String> args = new ArrayList<>();

    /** Python interpreter flags (e.g. {@code "-O"}, {@code "-X dev"}).
     *  Appended verbatim before the script file path. */
    private @Nullable String flags;
}
