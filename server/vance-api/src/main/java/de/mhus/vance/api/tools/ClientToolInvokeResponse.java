package de.mhus.vance.api.tools;

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
 * Client → brain: the result of a previous
 * {@link ClientToolInvokeRequest}, correlated by {@link #correlationId}.
 *
 * <p>Exactly one of {@link #result} or {@link #error} should be set.
 * Both empty is treated as "success with empty result" by the brain.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("tools")
public class ClientToolInvokeResponse {

    private String correlationId;

    @Builder.Default
    private Map<String, Object> result = new LinkedHashMap<>();

    private @Nullable String error;
}
