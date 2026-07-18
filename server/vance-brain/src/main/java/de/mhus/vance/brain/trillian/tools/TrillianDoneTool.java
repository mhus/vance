package de.mhus.vance.brain.trillian.tools;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.brain.frankie.FrankieTermination;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Worker termination signal for Trillian-spawned workers.
 *
 * <p>A worker that was spawned by Trillian-User via
 * {@code cross_process_create} (typically with recipe
 * {@code trillian-worker} on the Frankie engine) calls this tool
 * exactly once when its task is finished. The tool returns
 * {@code "_terminate": true}, which Frankie recognises as the
 * tool-driven termination signal in worker mode — the process
 * closes with {@code DONE}, the standard
 * {@code ParentNotificationListener} fires a DONE
 * {@code ProcessEvent} carrying the summary to the parent
 * (Trillian-User), which wakes and reports back to Control.
 *
 * <p>Without this tool, a Frankie worker would natural-stop into
 * {@code IDLE} after its final reply, never emitting a terminal
 * event — Trillian-User would never know the worker finished. The
 * tool is the explicit "task done forever" handshake.
 *
 * <p>Engine-role-agnostic — Frankie (or any engine that honours
 * the {@code _terminate} convention) consumes the result; Trillian
 * exposes it via the {@code trillian-worker} recipe's
 * {@code allowedToolsAdd}. Not added to any engine default.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TrillianDoneTool implements Tool {

    private final ChatMessageService chatMessageService;
    private final ThinkProcessService thinkProcessService;

    private static final Map<String, Object> SCHEMA;
    static {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("summary", Map.of(
                "type", "string",
                "description", "1-3 sentence statement of what you accomplished. "
                        + "This becomes the DONE event's humanSummary and is what "
                        + "Trillian-User sees as the result of your work — make "
                        + "it concrete and self-contained."));
        properties.put("data", Map.of(
                "type", "object",
                "description", "Optional structured payload (e.g. {count: 5, "
                        + "files: [...]}) for Trillian-User to consume "
                        + "programmatically. Free-form — use what fits."));
        SCHEMA = Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("summary"));
    }

    @Override
    public String name() {
        return "trillian_done";
    }

    @Override
    public String description() {
        return "Signal that this Trillian worker has finished its task. "
                + "Terminates the worker cleanly with DONE and delivers "
                + "the summary to Trillian-User. Call exactly once when "
                + "work is complete — do NOT natural-stop without this, "
                + "Trillian-User would never know you're done.";
    }

    @Override
    public boolean primary() {
        return true;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Set<String> labels() {
        return Set.of("executive");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String summary = stringOrThrow(params, "summary");
        Object data = params == null ? null : params.get("data");

        // Persist the summary as an ASSISTANT chat message before
        // Frankie terminates. Frankie at tool-terminate skips its
        // normal natural-stop persistAssistantReply path — without
        // this append the summary would only live in the langchain4j
        // tool-result history and ParentNotificationListener.
        // enrichWithLastReply (which reads from Mongo chat history)
        // would find nothing, leaving the parent with only the
        // generic engine summary.
        if (ctx.processId() != null && ctx.sessionId() != null) {
            try {
                ThinkProcessDocument process = thinkProcessService.findById(ctx.processId())
                        .orElse(null);
                if (process != null) {
                    chatMessageService.append(ChatMessageDocument.builder()
                            .tenantId(process.getTenantId())
                            .sessionId(process.getSessionId())
                            .thinkProcessId(process.getId())
                            .role(ChatRole.ASSISTANT)
                            .content(summary)
                            .build());
                }
            } catch (RuntimeException e) {
                log.warn("trillian_done: failed to persist summary as assistant message "
                                + "for process='{}': {}",
                        ctx.processId(), e.toString());
                // Don't fail the tool call — the _terminate signal
                // still goes through and the worker closes cleanly.
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        // Frankie's tool-driven terminate convention. The engine
        // checks for this key after every tool batch and exits with
        // CloseReason.DONE in worker mode.
        out.put(FrankieTermination.RESULT_TERMINATE_KEY, true);
        out.put("summary", summary);
        if (data != null) {
            out.put("data", data);
        }
        return out;
    }

    private static String stringOrThrow(@Nullable Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ToolException("'" + key + "' is required and must be a non-empty string");
        }
        return s.trim();
    }
}
