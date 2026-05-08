package de.mhus.vance.api.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload of a {@link MessageType#INTELLIJ_MCP_REGISTER} envelope.
 *
 * <p>Sent once after the welcome frame when foot is launched with the
 * {@code --intellij-mcp[=<url>]} switch. The brain upserts a
 * {@code mcp_server} {@code ServerToolDocument} pointing at the URL so
 * the IntelliJ MCP tool pack (run/debug/build/refactor/database/…)
 * becomes available to the LLM. Persistent — survives the WS session
 * and is overwritten on the next register-call with a different URL.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("ws")
public class IntellijMcpRegisterRequest {

    /**
     * Streamable-HTTP MCP endpoint to register, e.g.
     * {@code http://127.0.0.1:64342/stream}. Must include scheme,
     * host and path. Brain side validates loopback-only by default
     * (configurable per-tenant if remote MCP servers ever become a
     * use-case).
     */
    @NotBlank
    private String url;
}
