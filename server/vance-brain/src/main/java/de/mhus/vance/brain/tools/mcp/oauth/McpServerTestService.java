package de.mhus.vance.brain.tools.mcp.oauth;

import de.mhus.vance.api.servertools.ServerToolOAuthDto;
import de.mhus.vance.api.servertools.ServerToolTestResultDto;
import de.mhus.vance.brain.oauth.OAuthTokenOwner;
import de.mhus.vance.brain.tools.mcp.McpToolPackFactory;
import de.mhus.vance.brain.tools.types.ToolFactory;
import de.mhus.vance.brain.tools.types.ToolPackUnavailableException;
import de.mhus.vance.shared.servertool.ServerToolConfig;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Connection test and login state of MCP server tools for the admin UI
 * (the server-tool form view in the Settings "Server tools" area).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class McpServerTestService {

    private final McpToolPackFactory factory;
    private final McpOAuthService oauthService;

    /**
     * Login state of {@code config} for the caller, or {@code null} when the
     * tool is no MCP server with OAuth.
     *
     * @param canConnect whether the caller may log in for the tool's login scope
     */
    public @Nullable ServerToolOAuthDto oauthState(
            String tenant, String project, String userId, ServerToolConfig config, boolean canConnect) {
        Optional<McpOAuthSettings> settings = oauthSettings(config);
        if (settings.isEmpty()) return null;
        OAuthTokenOwner owner;
        try {
            owner = settings.get().owner(project, userId);
        } catch (ToolPackUnavailableException e) {
            // A project login viewed in _tenant: there is no account to show
            // or to log in to here.
            return ServerToolOAuthDto.builder()
                    .providerId(settings.get().providerId())
                    .loginScope(settings.get().loginScope().wireName())
                    .status(McpOAuthService.STATUS_NOT_CONNECTED)
                    .canConnect(false)
                    .build();
        }
        return ServerToolOAuthDto.builder()
                .providerId(settings.get().providerId())
                .loginScope(settings.get().loginScope().wireName())
                .status(oauthService.status(tenant, settings.get(), owner))
                .canConnect(canConnect)
                .build();
    }

    /**
     * Opens the server as the caller and lists its tools. For an OAuth server
     * without login the result is what the discovery finds instead.
     */
    public ServerToolTestResultDto runConnectionTest(
            String tenant, String project, String userId, ServerToolConfig config) {
        if (!McpToolPackFactory.TYPE_ID.equals(config.type())) {
            return failure("only MCP server tools can be tested");
        }
        Optional<McpOAuthSettings> oauth;
        try {
            oauth = McpOAuthSettings.of(config.parameters());
        } catch (IllegalArgumentException e) {
            return failure(e.getMessage());
        }
        ToolInvocationContext ctx = new ToolInvocationContext(tenant, project, null, null, userId);
        try {
            Collection<Tool> tools = factory.create(config.toTransientDocument(tenant, project), ctx);
            String prefix = config.name() + ToolFactory.PACK_SEPARATOR;
            List<String> names = new ArrayList<>();
            for (Tool tool : tools) {
                names.add(tool.name().startsWith(prefix) ? tool.name().substring(prefix.length()) : tool.name());
            }
            names.sort(String::compareTo);
            return ServerToolTestResultDto.builder()
                    .ok(true)
                    .authRequired(oauth.isPresent())
                    .tools(names)
                    .build();
        } catch (ToolPackUnavailableException e) {
            return discoveryReport(tenant, config, oauth.orElseThrow(), e.getMessage());
        } catch (RuntimeException e) {
            log.info(
                    "MCP server test failed: tenant='{}' project='{}' tool='{}': {}",
                    tenant,
                    project,
                    config.name(),
                    e.toString());
            return failure(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private ServerToolTestResultDto discoveryReport(
            String tenant, ServerToolConfig config, McpOAuthSettings settings, @Nullable String reason) {
        try {
            McpOAuthService.Discovery discovery = oauthService.inspect(tenant, settings, config.parameters());
            return ServerToolTestResultDto.builder()
                    .ok(false)
                    .error(reason)
                    .authRequired(true)
                    .issuer(discovery.issuer())
                    .registration(discovery.registration())
                    .scopes(discovery.scopes())
                    .build();
        } catch (McpOAuthException e) {
            return ServerToolTestResultDto.builder()
                    .ok(false)
                    .error(e.getMessage())
                    .authRequired(true)
                    .build();
        }
    }

    private static Optional<McpOAuthSettings> oauthSettings(ServerToolConfig config) {
        if (!McpToolPackFactory.TYPE_ID.equals(config.type())) return Optional.empty();
        try {
            return McpOAuthSettings.of(config.parameters());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static ServerToolTestResultDto failure(@Nullable String message) {
        return ServerToolTestResultDto.builder().ok(false).error(message).build();
    }
}
