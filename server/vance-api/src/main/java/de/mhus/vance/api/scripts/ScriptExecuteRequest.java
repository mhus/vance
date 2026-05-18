package de.mhus.vance.api.scripts;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /brain/{tenant}/scripts/execute?projectId=…}.
 *
 * <p>Either {@code scriptId} or {@code code} must be set. With
 * {@code scriptId} the server loads the current inline content;
 * otherwise {@code code} is used verbatim.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("scripts")
public class ScriptExecuteRequest {

    private @Nullable String scriptId;
    private @Nullable String code;
    private @Nullable String sourceName;

    /** Script-arg map; exposed to the script as {@code args} global. */
    @Builder.Default
    private Map<String, Object> args = new LinkedHashMap<>();

    /** Hard timeout in milliseconds. {@code null} → server default (30s). */
    private @Nullable Long timeoutMs;
}
