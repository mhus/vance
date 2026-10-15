package de.mhus.vance.api.servertools;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Answer of {@code POST oauth-client/mcp/init}: the login of an MCP server
 * tool has been started, and the browser navigates to the authorization
 * server's authorize URL itself.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@GenerateTypeScript("server-tools")
public class ServerToolOAuthStartDto {

    /** Authorize URL of the authorization server, carrying state, PKCE challenge and {@code resource}. */
    private String authorizationUrl;
}
