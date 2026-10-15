package de.mhus.vance.api.servertools;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Login state of an MCP server tool with {@code auth.type: oauth}, as seen
 * by the caller: the account the login scope selects — the caller's own,
 * the project's or the tenant's.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("server-tools")
public class ServerToolOAuthDto {

    /** OAuth provider id derived from the server URL ({@code mcp-<host>-<hash>}). */
    private String providerId;

    /** {@code user}, {@code project} or {@code tenant} — whose account the server is used with. */
    private String loginScope;

    /** {@code CONNECTED}, {@code NOT_CONNECTED} or {@code EXPIRED}. */
    private String status;

    /** Whether the caller may log in / log out for this login scope. */
    private boolean canConnect;
}
