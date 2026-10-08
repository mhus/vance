package de.mhus.vance.brain.mcpserver;

import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.core.McpJsonRpc;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * JSON-RPC method dispatch for the <b>project-scoped</b> MCP surface
 * ({@code POST /brain/{tenant}/mcp/{project}}) — the narrow, config-
 * regulated counter-piece to {@link McpServerService}. The protocol
 * shell is shared ({@link McpProtocol}); this class is the surface:
 *
 * <ul>
 *   <li>{@code tools/list} answers with the fixed inventory from
 *       {@link McpProjectToolCatalog}, filtered by the access mode the
 *       caller's config entry grants — or an empty catalogue when no
 *       entry matches (deny).</li>
 *   <li>{@code tools/call} validates the call against the same entry
 *       (mode, path prefixes, path-only addressing, project pin) and
 *       then invokes the tool <b>directly</b>, not through
 *       {@code ToolDispatcher} — the dispatcher's coarse EXECUTE gate
 *       would demand WRITER even for reads, and read-only access for
 *       READER accounts is exactly the property this surface exists
 *       for. Authorization instead happens where it is precise: the
 *       endpoint's READ gate, project resolution, and the write tools'
 *       own per-document WRITE enforcement (reserved {@code _vance/}
 *       paths need ADMIN, rule R4). The dispatcher's other gate, the
 *       Shooty TOOL point ({@code ToolGuardGate}), is not lost either:
 *       it covers only the exec-run family and only calls with a
 *       process context — neither applies to this process-less,
 *       document-only surface. Widening the TOOL point to doc tools
 *       or process-less calls means adding the gate here.</li>
 * </ul>
 *
 * <p>Denials are always normal MCP responses: {@code isError:true}
 * content for calls, an empty catalogue for listings — an external
 * agent must see "no access" as text, not as a protocol or HTTP
 * failure. The config is a ceiling, never a grant: whatever it allows
 * still runs into the caller's real permissions.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class McpProjectService {

    /** Reserved prefix that no write through this surface may ever touch. */
    static final String RESERVED_PREFIX = "_vance/";

    private final McpProjectAccessService accessService;
    private final McpProjectToolCatalog catalog;
    private final ObjectMapper objectMapper;

    /**
     * Handle one JSON-RPC frame for the caller identified by
     * {@code username} and, for integration tokens, {@code tokenId}.
     */
    public McpProtocol.Outcome handle(
            String body, String tenant, String project, @Nullable String username, @Nullable String tokenId) {
        return McpProtocol.handle(body, req -> dispatch(req, tenant, project, username, tokenId));
    }

    private Object dispatch(
            McpJsonRpc.Frame.Request req,
            String tenant,
            String project,
            @Nullable String username,
            @Nullable String tokenId) {
        return switch (req.method()) {
            case "initialize" -> McpProtocol.initialize(req.params());
            case "ping" -> Map.of();
            case "tools/list" -> toolsList(tenant, project, tokenId);
            case "tools/call" -> toolsCall(req.params(), tenant, project, username, tokenId);
            default ->
                throw new McpProtocol.RpcError(McpProtocol.METHOD_NOT_FOUND, "Method not found: " + req.method());
        };
    }

    // ──────────────────── methods ────────────────────

    private Map<String, Object> toolsList(String tenant, String project, @Nullable String tokenId) {
        McpProjectAccessConfig.Entry entry = accessService.resolveEntry(tenant, project, tokenId);
        if (entry == null) {
            log.debug("MCP project tools/list denied: no access entry for '{}/{}'", tenant, project);
            return McpProtocol.toolsListResult(List.of());
        }
        List<Tool> tools = catalog.forMode(entry.mode()).stream()
                .map(McpProjectToolCatalog.CatalogEntry::tool)
                .toList();
        return McpProtocol.toolsListResult(tools);
    }

    private Map<String, Object> toolsCall(
            @Nullable Map<String, Object> params,
            String tenant,
            String project,
            @Nullable String username,
            @Nullable String tokenId) {
        McpProtocol.ToolCall call = McpProtocol.parseToolCall(params);
        McpProjectAccessConfig.Entry entry = accessService.resolveEntry(tenant, project, tokenId);
        if (entry == null) {
            log.debug("MCP project tools/call '{}' denied: no access entry for '{}/{}'", call.name(), tenant, project);
            return McpProtocol.callContent(
                    "mcp access denied: no entry in " + McpProjectAccessService.CONFIG_PATH + " matches this caller",
                    true);
        }
        McpProjectToolCatalog.CatalogEntry catalogEntry =
                catalog.find(call.name()).orElse(null);
        if (catalogEntry == null) {
            return McpProtocol.callContent("Unknown tool on this surface: " + call.name(), true);
        }
        if (catalogEntry.write() && entry.mode() == McpProjectAccessConfig.Mode.RO) {
            log.debug("MCP project tools/call '{}' denied: mode 'ro' (entry '{}')", call.name(), entry.name());
            return McpProtocol.callContent(
                    "Tool '" + call.name() + "' is not available in access mode 'ro'" + " (entry '" + entry.name()
                            + "')",
                    true);
        }
        String violation = violationOf(catalogEntry, entry, call.arguments(), project);
        if (violation != null) {
            log.debug("MCP project tools/call '{}' denied by surface: {}", call.name(), violation);
            return McpProtocol.callContent(violation, true);
        }
        return invoke(catalogEntry, call, tenant, project, username);
    }

    // ──────────────────── invocation ────────────────────

    private Map<String, Object> invoke(
            McpProjectToolCatalog.CatalogEntry catalogEntry,
            McpProtocol.ToolCall call,
            String tenant,
            String project,
            @Nullable String username) {
        ToolInvocationContext ctx = new ToolInvocationContext(tenant, project, null, null, username);
        try {
            Map<String, Object> result = catalogEntry.tool().invoke(call.arguments(), ctx);
            return McpProtocol.callContent(json(result), false);
        } catch (ToolException e) {
            // Same convention as the global surface: a tool that ran but
            // failed is a successful JSON-RPC response with isError=true.
            String msg = e.getMessage() == null ? "Tool failed" : e.getMessage();
            log.debug("MCP project tools/call '{}' failed: {}", call.name(), msg);
            return McpProtocol.callContent(withHint(msg, catalogEntry.tool().troubleshootingHint()), true);
        } catch (DocumentService.DocumentLockedException e) {
            // Soft document-lock — surface as a clean, recognizable error
            // so the calling agent can decide to ask the owner.
            String lockedFor = e.getLockedFor().stream()
                    .sorted()
                    .map(Enum::name)
                    .reduce((a, b) -> a + "," + b)
                    .orElse("");
            log.info(
                    "MCP project tools/call '{}' rejected by document lock blocked={} lockedFor={}",
                    call.name(),
                    e.getBlockedRole(),
                    lockedFor);
            return McpProtocol.callContent(
                    "document_locked: write blocked because the document's lockedFor set contains "
                            + e.getBlockedRole() + " (full set: [" + lockedFor
                            + "]). The document is locked against edits; ask the owner to unlock it.",
                    true);
        } catch (RuntimeException e) {
            log.warn("MCP project tools/call '{}' raised: {}", call.name(), e.toString(), e);
            return McpProtocol.callContent("Tool '" + call.name() + "' failed: " + e.getMessage(), true);
        }
    }

    // ──────────────────── surface regulation ────────────────────

    /**
     * Checks one call against the surface's confinement rules. Returns a
     * caller-visible denial message, or {@code null} when the call may
     * proceed.
     */
    private static @Nullable String violationOf(
            McpProjectToolCatalog.CatalogEntry catalogEntry,
            McpProjectAccessConfig.Entry entry,
            Map<String, Object> args,
            String routeProject) {
        // Path-only addressing: doc_read by id checks only the tenant,
        // and id addressing would sidestep every prefix rule anyway.
        if (stringArg(args, "id") != null || stringArg(args, "documentId") != null) {
            return "documents are addressed by 'path' only on this surface;"
                    + " 'id'/'documentId' arguments are not accepted";
        }
        String explicitProject = stringArg(args, "projectId");
        if (explicitProject != null && !explicitProject.equals(routeProject)) {
            return "this surface is pinned to project '" + routeProject + "'; 'projectId' must match it or be omitted";
        }
        List<String> scopes = scopesOf(catalogEntry, args);
        if (!entry.isWholeProject()) {
            if (scopes.isEmpty()) {
                return "access entry '" + entry.name() + "' is path-scoped; pass an explicit"
                        + " path argument within its prefixes: " + entry.paths();
            }
            for (String scope : scopes) {
                if (scope.isEmpty()) {
                    return "access entry '" + entry.name() + "' is path-scoped;"
                            + " whole-project scope ('*') is not allowed";
                }
                if (!entry.pathAllowed(scope)) {
                    return "path '" + scope + "' is outside the prefixes of access entry '" + entry.name() + "': "
                            + entry.paths();
                }
            }
        }
        // The reserved area is never writable through this surface —
        // hard, not configurable, so an rw entry cannot rewrite the
        // config that regulates it. (Reads there follow the prefixes.)
        if (catalogEntry.write() && scopes.stream().anyMatch(McpProjectService::isReserved)) {
            return "the reserved '" + RESERVED_PREFIX + "' area is never writable" + " through this surface";
        }
        return null;
    }

    /**
     * Every document-path scope the call names, from all of the entry's
     * path-carrying params that are present — a tool with aliased params
     * decides on its own which one wins, so each must pass. Empty list =
     * none given (tool default applies, which path-scoped entries
     * reject); an empty string element = the whole-project wildcard
     * {@code *}.
     */
    private static List<String> scopesOf(McpProjectToolCatalog.CatalogEntry entry, Map<String, Object> args) {
        List<String> scopes = new ArrayList<>();
        for (String param : entry.pathParams()) {
            String value = stringArg(args, param);
            if (value == null) continue;
            scopes.add("*".equals(value) ? "" : normalizePath(value));
        }
        return scopes;
    }

    private static boolean isReserved(String scope) {
        return scope.startsWith(RESERVED_PREFIX)
                || scope.equals(RESERVED_PREFIX.substring(0, RESERVED_PREFIX.length() - 1));
    }

    private static @Nullable String stringArg(Map<String, Object> args, String key) {
        return args.get(key) instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    /**
     * Strips <em>every</em> leading slash, as {@code DocumentService}'s
     * own normalisation does — the surface must judge the path the store
     * will actually use, or {@code //_vance/...} slips past the reserved
     * check and lands in {@code _vance/...}. Trailing slashes stay: they
     * are meaningful for prefix scopes ({@code docs/} vs. {@code docs}),
     * and {@link #isReserved} covers the bare {@code _vance} form.
     */
    private static String normalizePath(String path) {
        String trimmed = path.trim();
        while (trimmed.startsWith("/")) trimmed = trimmed.substring(1);
        return trimmed;
    }

    private static String withHint(String message, @Nullable String hint) {
        if (hint == null || hint.isBlank()) return message;
        return message + " -- hint: " + hint;
    }

    // ──────────────────── helpers ────────────────────

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            return String.valueOf(value);
        }
    }
}
