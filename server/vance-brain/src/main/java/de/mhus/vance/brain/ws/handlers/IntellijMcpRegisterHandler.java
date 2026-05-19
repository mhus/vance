package de.mhus.vance.brain.ws.handlers;

import de.mhus.vance.api.ws.IntellijMcpRegisterRequest;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.servertool.ServerToolService;
import de.mhus.vance.brain.tools.mcp.McpToolPackFactory;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.brain.ws.WsHandler;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.servertool.ServerToolDocument;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/**
 * Handles {@link MessageType#INTELLIJ_MCP_REGISTER}: foot announces a
 * JetBrains-MCP HTTP endpoint, the brain upserts a {@code mcp_server}
 * {@code ServerToolDocument} in the tenant's {@code _vance} system project
 * so the IntelliJ tools become available to every project in the tenant.
 *
 * <p>Idempotent — same URL re-registers as a no-op, different URL updates
 * the existing doc in place. Cleanup is best-effort: the doc persists
 * across sessions; if IntelliJ moves to a new port the next register-call
 * overwrites the URL. Manually delete the {@code intellij_mcp} server-tool
 * document if you want it gone for good.
 *
 * <p>Safety: only loopback URLs are accepted in v1 — the brain does
 * outbound HTTP to whatever the foot specifies, so trusting an
 * arbitrary URL would be a server-side request-forgery vector. Lifting
 * the loopback restriction is a per-tenant policy decision.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IntellijMcpRegisterHandler implements WsHandler {

    /** Stable name of the upserted document; used as the cascade lookup key. */
    public static final String TOOL_NAME = "intellij_mcp";

    private final ObjectMapper objectMapper;
    private final WebSocketSender sender;
    private final ServerToolService serverToolService;

    @Override
    public String type() {
        return MessageType.INTELLIJ_MCP_REGISTER;
    }

    @Override
    public void handle(
            ConnectionContext ctx, WebSocketSession wsSession, WebSocketEnvelope envelope)
            throws IOException {
        IntellijMcpRegisterRequest request;
        try {
            request = objectMapper.convertValue(
                    envelope.getData(), IntellijMcpRegisterRequest.class);
        } catch (RuntimeException e) {
            sender.sendError(wsSession, envelope, 400,
                    "Invalid intellij-mcp-register payload: " + e.getMessage());
            return;
        }
        if (request == null || request.getUrl() == null || request.getUrl().isBlank()) {
            sender.sendError(wsSession, envelope, 400, "url is required");
            return;
        }
        String url = request.getUrl().trim();
        String validationError = validateLoopback(url);
        if (validationError != null) {
            sender.sendError(wsSession, envelope, 400, validationError);
            return;
        }

        String tenantId = ctx.getTenantId();
        String projectId = HomeBootstrapService.TENANT_PROJECT_NAME;
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("transport", "http");
        parameters.put("url", url);

        Optional<ServerToolDocument> existing =
                serverToolService.findDocument(tenantId, projectId, TOOL_NAME);
        if (existing.isPresent()) {
            ServerToolDocument current = existing.get();
            String currentUrl = currentUrlOf(current);
            if (url.equals(currentUrl)) {
                log.debug("intellij-mcp-register: tenant='{}' url='{}' — already registered, no-op",
                        tenantId, url);
            } else {
                ServerToolDocument updated = ServerToolDocument.builder()
                        .name(TOOL_NAME)
                        .type(McpToolPackFactory.TYPE_ID)
                        .description(description())
                        .parameters(parameters)
                        .labels(List.of("ide", "intellij"))
                        .enabled(true)
                        .primary(true)
                        .build();
                serverToolService.update(tenantId, projectId, TOOL_NAME, updated);
                log.info("intellij-mcp-register: tenant='{}' url updated '{}' → '{}'",
                        tenantId, currentUrl, url);
            }
        } else {
            ServerToolDocument doc = ServerToolDocument.builder()
                    .name(TOOL_NAME)
                    .type(McpToolPackFactory.TYPE_ID)
                    .description(description())
                    .parameters(parameters)
                    .labels(List.of("ide", "intellij"))
                    .enabled(true)
                    .primary(true)
                    .build();
            serverToolService.create(tenantId, projectId, doc);
            log.info("intellij-mcp-register: tenant='{}' created '{}' → '{}'",
                    tenantId, TOOL_NAME, url);
        }

        sender.sendReply(wsSession, envelope, MessageType.INTELLIJ_MCP_REGISTER, null);
    }

    private static String description() {
        return "JetBrains IntelliJ IDE — run/debug/build/refactor/database tools "
                + "exposed by the built-in MCP Server plugin.";
    }

    private static String currentUrlOf(ServerToolDocument doc) {
        Object u = doc.getParameters() == null ? null : doc.getParameters().get("url");
        return u instanceof String s ? s : "";
    }

    /**
     * Rejects non-loopback hosts and obviously broken URLs. The brain
     * makes outbound HTTP to whatever lands here; restricting to
     * {@code 127.0.0.1} / {@code localhost} keeps a malicious or
     * compromised foot from pointing the brain at internal services.
     */
    static String validateLoopback(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return "url is not a valid URI: " + e.getMessage();
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return "url scheme must be http or https";
        }
        String host = uri.getHost();
        if (host == null) {
            return "url has no host";
        }
        // URI.getHost() wraps IPv6 in brackets (e.g. "[::1]"); strip them
        // so the comparison stays simple and string-based.
        String h = host.toLowerCase();
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        if (!(h.equals("127.0.0.1") || h.equals("localhost") || h.equals("::1"))) {
            return "url host must be loopback (127.0.0.1, ::1, or localhost), got: " + host;
        }
        return null;
    }
}
