package de.mhus.vance.brain.mcpserver;

import de.mhus.vance.brain.trillian.exposure.TrillianConversationService;
import de.mhus.vance.brain.trillian.exposure.TrillianExposureService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The fixed tool inventory of the agents door
 * ({@code POST /brain/{tenant}/agents}) — the conversation surface for
 * external counterparts of exposed Trillians. Deliberately <b>not</b> brain
 * tools: the entries are plain objects this catalog constructs, so the
 * ServerToolRegistry never sees them — no recipe, no engine and no other
 * surface can reach them, and the door cannot grow a second purpose by
 * accident (the same discipline the project-scoped surface applies with its
 * fixed {@code doc_*} list).
 *
 * <p>Four tools, one purpose ({@code planning/trillian-exposure.md} §3.8):
 * {@code agent_list} (the directory), {@code agent_send} (open a
 * conversation or contribute, waiting for the head's answer with long-poll),
 * {@code agent_read} (new contributions since a cursor, with long-poll),
 * {@code agent_close} (end a conversation you are done with).
 * The Trillian is chosen by its exposed name as a call argument — the door
 * is one route, not one route per agent.
 */
@Component
public class TrillianAgentsToolCatalog {

    private final TrillianExposureService exposureService;
    private final TrillianConversationService conversations;

    /** Constructed once, in order — the order is the tools/list order. */
    private final List<Tool> tools;

    public TrillianAgentsToolCatalog(
            TrillianExposureService exposureService, TrillianConversationService conversations) {
        this.exposureService = exposureService;
        this.conversations = conversations;
        this.tools = List.of(new AgentListTool(), new AgentSendTool(), new AgentReadTool(), new AgentCloseTool());
    }

    /** The fixed inventory. */
    public List<Tool> tools() {
        return tools;
    }

    @Nullable
    Tool find(String name) {
        for (Tool tool : tools) {
            if (tool.name().equals(name)) return tool;
        }
        return null;
    }

    // ──────────────────── the tools ────────────────────

    /** {@code agent_list} — the exposed Trillians of the tenant. */
    private class AgentListTool implements Tool {

        @Override
        public String name() {
            return "agent_list";
        }

        @Override
        public String description() {
            return "List the exposed agents (Trillians) of this tenant you may talk to"
                    + " through this door: exposed name, description, open doors.";
        }

        @Override
        public boolean primary() {
            return true;
        }

        @Override
        public Map<String, Object> paramsSchema() {
            return Map.of("type", "object", "properties", Map.of());
        }

        @Override
        public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
            // Only who this caller may talk to through this door: an agent
            // that refuses the caller anyway is no entry in their directory,
            // and the list does not tell anyone whom else a Trillian talks to.
            String caller = callerOf(ctx);
            List<Map<String, Object>> agents = new ArrayList<>();
            exposureService.directory(ctx.tenantId()).stream()
                    .filter(exposed ->
                            exposed.config().doors().mcp() && exposed.config().allows(caller))
                    .forEach(exposed -> {
                        Map<String, Object> doors = new LinkedHashMap<>();
                        doors.put("mcp", exposed.config().doors().mcp());
                        doors.put("a2a", exposed.config().doors().a2a());
                        Map<String, Object> agent = new LinkedHashMap<>();
                        agent.put("name", exposed.config().exposedName());
                        agent.put("description", exposed.config().description());
                        agent.put("doors", doors);
                        agents.add(agent);
                    });
            return Map.of("agents", agents);
        }
    }

    /** {@code agent_send} — open a conversation or contribute, then wait for the answer. */
    private class AgentSendTool implements Tool {

        @Override
        public String name() {
            return "agent_send";
        }

        @Override
        public String description() {
            return "Open a conversation with an exposed agent (arguments: agent 'name' plus 'text',"
                    + " optional 'title') or contribute to one you opened before (arguments:"
                    + " 'conversationId' plus 'text'). Waits for the agent's answer up to"
                    + " 'waitMs' milliseconds; when nothing arrives in time, the result says so"
                    + " and agent_read with the same conversationId picks the answer up later.";
        }

        @Override
        public boolean primary() {
            return true;
        }

        @Override
        public Map<String, Object> paramsSchema() {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put(
                    "name",
                    Map.of(
                            "type",
                            "string",
                            "description",
                            "Exposed name of the agent — required to open a conversation"));
            props.put(
                    "conversationId",
                    Map.of("type", "string", "description", "Existing conversation — required to contribute"));
            props.put(
                    "title",
                    Map.of(
                            "type",
                            "string",
                            "description",
                            "Title of a new conversation; omitted = first line of the text"));
            props.put("text", Map.of("type", "string", "description", "The contribution"));
            props.put(
                    "waitMs",
                    Map.of(
                            "type",
                            "integer",
                            "description",
                            "How long to wait for the answer (default 20000, max 55000)"));
            return Map.of("type", "object", "properties", props, "required", List.of("text"));
        }

        @Override
        public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
            String caller = callerOf(ctx);
            String text = requireString(params, "text");
            long waitMs = waitOf(params);
            String conversationId = stringOf(params, "conversationId");
            String cursor;
            if (conversationId != null) {
                cursor = conversations
                        .send(ctx.tenantId(), caller, conversationId, text)
                        .id();
            } else {
                String name = requireString(params, "name");
                conversationId = conversations
                        .open(ctx.tenantId(), caller, name, stringOf(params, "title"), text)
                        .conversationId();
                cursor = null; // a fresh conversation has no messages yet
            }
            TrillianConversationService.Reply reply =
                    conversations.read(ctx.tenantId(), caller, conversationId, cursor, waitMs);
            return replyResult(reply);
        }
    }

    /** {@code agent_read} — contributions since a cursor, with long-poll. */
    private class AgentReadTool implements Tool {

        @Override
        public String name() {
            return "agent_read";
        }

        @Override
        public String description() {
            return "Read a conversation you opened with an exposed agent: all contributions, or"
                    + " only those after the message id 'after'. Waits up to 'waitMs' milliseconds"
                    + " when nothing is new yet. Reading stays possible after the conversation"
                    + " was archived; contributing does not. 'closed: true' in the result means the"
                    + " conversation has ended — nothing new will arrive.";
        }

        @Override
        public boolean primary() {
            return true;
        }

        @Override
        public Map<String, Object> paramsSchema() {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("conversationId", Map.of("type", "string", "description", "The conversation to read"));
            props.put(
                    "after",
                    Map.of(
                            "type",
                            "string",
                            "description",
                            "Message id cursor — only contributions after it; omitted = all"));
            props.put(
                    "waitMs",
                    Map.of(
                            "type",
                            "integer",
                            "description",
                            "How long to wait for something new (default 20000, max 55000)"));
            return Map.of("type", "object", "properties", props, "required", List.of("conversationId"));
        }

        @Override
        public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
            String conversationId = requireString(params, "conversationId");
            TrillianConversationService.Reply reply = conversations.read(
                    ctx.tenantId(), callerOf(ctx), conversationId, stringOf(params, "after"), waitOf(params));
            return replyResult(reply);
        }
    }

    /** {@code agent_close} — end a conversation the caller is done with. */
    private class AgentCloseTool implements Tool {

        @Override
        public String name() {
            return "agent_close";
        }

        @Override
        public String description() {
            return "End a conversation you opened with an exposed agent when you are done with it."
                    + " It stays readable with agent_read; a new concern is a new conversation."
                    + " Conversations you leave alone are closed after a while of idleness.";
        }

        @Override
        public boolean primary() {
            return true;
        }

        @Override
        public Map<String, Object> paramsSchema() {
            return Map.of(
                    "type",
                    "object",
                    "properties",
                    Map.of("conversationId", Map.of("type", "string", "description", "The conversation to end")),
                    "required",
                    List.of("conversationId"));
        }

        @Override
        public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
            String conversationId = requireString(params, "conversationId");
            conversations.close(ctx.tenantId(), callerOf(ctx), conversationId);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("conversationId", conversationId);
            out.put("closed", true);
            return out;
        }
    }

    // ──────────────────── argument helpers ────────────────────

    private static Map<String, Object> replyResult(TrillianConversationService.Reply reply) {
        List<Map<String, Object>> messages = new ArrayList<>();
        for (TrillianConversationService.Contribution message : reply.messages()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", message.id());
            m.put("author", message.author());
            m.put("body", message.body());
            m.put("at", message.at() == null ? null : message.at().toString());
            messages.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("conversationId", reply.conversationId());
        out.put("arrived", reply.arrived());
        out.put("closed", reply.closed());
        out.put("messages", messages);
        return out;
    }

    private static String callerOf(ToolInvocationContext ctx) {
        return ctx.userId() == null ? "" : ctx.userId();
    }

    private static @Nullable String stringOf(Map<String, Object> params, String key) {
        return params.get(key) instanceof String s && !s.isBlank() ? s : null;
    }

    private static String requireString(Map<String, Object> params, String key) {
        String value = stringOf(params, key);
        if (value == null) {
            throw new ToolException("'" + key + "' is required");
        }
        return value;
    }

    private static long waitOf(Map<String, Object> params) {
        return params.get("waitMs") instanceof Number n
                ? TrillianConversationService.clampWait(n.longValue())
                : TrillianConversationService.DEFAULT_WAIT_MS;
    }
}
