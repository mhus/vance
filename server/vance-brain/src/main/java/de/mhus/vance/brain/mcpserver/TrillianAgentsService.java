package de.mhus.vance.brain.mcpserver;

import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.core.McpJsonRpc;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * JSON-RPC method dispatch for the agents door
 * ({@code POST /brain/{tenant}/agents}) — the conversation surface for
 * external counterparts of exposed Trillians. The protocol shell is shared
 * ({@link McpProtocol}); this class is the surface: {@code tools/list}
 * answers with the fixed inventory from {@link TrillianAgentsToolCatalog},
 * {@code tools/call} invokes directly — no ToolDispatcher, no Shooty TOOL
 * point, for the same reason as the project-scoped surface: the gate that
 * matters here is the exposure configuration, checked inside the protocol
 * entry ({@code TrillianConversationService}), not a process-context
 * permission.
 *
 * <p>Denials are always normal MCP responses — {@code isError:true} content
 * with the entry's stable reason ("not allowed", "agent inactive", …) — an
 * external agent must see a refusal as text, not as an HTTP failure. Same
 * convention as the other MCP surfaces.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TrillianAgentsService {

    private final TrillianAgentsToolCatalog catalog;
    private final ObjectMapper objectMapper;

    /** Handles one JSON-RPC frame for the caller identified by {@code username}. */
    public McpProtocol.Outcome handle(String body, String tenant, @Nullable String username) {
        return McpProtocol.handle(body, req -> dispatch(req, tenant, username));
    }

    private Object dispatch(McpJsonRpc.Frame.Request req, String tenant, @Nullable String username) {
        return switch (req.method()) {
            case "initialize" -> McpProtocol.initialize(req.params());
            case "ping" -> Map.of();
            case "tools/list" -> McpProtocol.toolsListResult(catalog.tools());
            case "tools/call" -> toolsCall(req.params(), tenant, username);
            default ->
                throw new McpProtocol.RpcError(McpProtocol.METHOD_NOT_FOUND, "Method not found: " + req.method());
        };
    }

    private Map<String, Object> toolsCall(
            @Nullable Map<String, Object> params, String tenant, @Nullable String username) {
        McpProtocol.ToolCall call = McpProtocol.parseToolCall(params);
        Tool tool = catalog.find(call.name());
        if (tool == null) {
            return McpProtocol.callContent("Unknown tool on this surface: " + call.name(), true);
        }
        ToolInvocationContext ctx = new ToolInvocationContext(tenant, null, null, null, username);
        try {
            Map<String, Object> result = tool.invoke(call.arguments(), ctx);
            return McpProtocol.callContent(json(result), false);
        } catch (ToolException e) {
            // A refusal is protocol content: the gate's stable reason, not a failure.
            String msg = e.getMessage() == null ? "Tool failed" : e.getMessage();
            log.debug("agents tools/call '{}' refused or failed: {}", call.name(), msg);
            return McpProtocol.callContent(msg, true);
        } catch (RuntimeException e) {
            log.warn("agents tools/call '{}' raised: {}", call.name(), e.toString(), e);
            return McpProtocol.callContent("Tool '" + call.name() + "' failed: " + e.getMessage(), true);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            return String.valueOf(value);
        }
    }
}
