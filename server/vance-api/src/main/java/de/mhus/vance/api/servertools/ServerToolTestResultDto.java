package de.mhus.vance.api.servertools;

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
 * Result of {@code POST …/server-tools/{name}/test} for an MCP server: the
 * tools the server lists for the caller, or — when the caller is not
 * logged in yet — what the OAuth discovery found.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("server-tools")
public class ServerToolTestResultDto {

    /** {@code true} when the connection worked and {@link #tools} is the server's list. */
    private boolean ok;

    /** Human-readable failure, {@code null} on success. */
    private @Nullable String error;

    /** Local names of the tools the server offers. */
    @Builder.Default
    private List<String> tools = new ArrayList<>();

    /** Whether the server demands an OAuth login (answered 401). */
    private boolean authRequired;

    /** Issuer of the server's authorization server, when discovered. */
    private @Nullable String issuer;

    /** How a client id would be obtained: {@code cimd}, {@code dcr}, {@code manual} or {@code none}. */
    private @Nullable String registration;

    /** Scopes the server asks for or supports. */
    @Builder.Default
    private List<String> scopes = new ArrayList<>();
}
