package de.mhus.vance.api.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Brain → client: invoke a client-registered tool.
 *
 * <p>The client must answer with a {@link ClientToolInvokeResponse}
 * carrying the same {@link #correlationId}. Envelope-level
 * {@code replyTo} could carry this instead — {@code correlationId} is
 * duplicated here so the tool plumbing is usable without assuming the
 * envelope shape.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("tools")
public class ClientToolInvokeRequest {

    private String correlationId;
    private String name;

    @Builder.Default
    private Map<String, Object> params = new LinkedHashMap<>();
}
