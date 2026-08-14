package de.mhus.vance.brain.trillian.tools;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.brain.frankie.FrankieTermination;
import de.mhus.vance.brain.trillian.TrillianWorkerEngine;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Lets a Trillian worker ask a question without ending itself.
 *
 * <p><b>Why a tool and not an instruction.</b> The worker prompt used to
 * say: to ask, reply in plain text and call nothing. A natural stop
 * leaves the process IDLE, which is exactly what was wanted. The model
 * asked through {@code trillian_done} anyway and closed itself, taking
 * everything it had worked out with it. In a tool-calling loop, "end the
 * turn by calling nothing" competes with every tool on the list and
 * loses — so asking gets a tool of its own, and the choice becomes one
 * between two named things rather than between a tool and an omission.
 *
 * <p>Mechanically this is the same exit as {@code trillian_done}: it
 * returns Frankie's {@code _terminate} so the loop stops. The difference
 * is what the engine does next — {@link TrillianWorkerEngine} sees the
 * marker left here and parks the worker IDLE instead of closing it, so
 * {@code process_steer} can carry the answer straight back into the
 * context that raised the question.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TrillianAskTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "question", Map.of(
                            "type", "string",
                            "description", "What you need to know, in one or two "
                                    + "sentences. Say what you already established and "
                                    + "what you would do with each possible answer — "
                                    + "whoever reads it cannot see your work.")),
            "required", List.of("question"));

    private final ThinkProcessService thinkProcessService;
    private final ChatMessageService chatMessageService;

    @Override
    public String name() {
        return "trillian_ask";
    }

    @Override
    public String description() {
        return "Ask a question and wait. Use this when you cannot continue "
                + "without something only a human can decide — a blocked "
                + "target, a choice between two paths, a missing fact. You "
                + "stay alive with everything you have worked out; the answer "
                + "is delivered back to you and you carry on from where you "
                + "stopped. Do NOT use trillian_done for a question: that "
                + "ends you, and your work is lost.";
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
    public Map<String, Object> invoke(
            @Nullable Map<String, Object> params, ToolInvocationContext ctx) {
        Object raw = params == null ? null : params.get("question");
        if (!(raw instanceof String question) || question.isBlank()) {
            throw new ToolException("'question' is required and must be a non-empty string");
        }
        String text = question.trim();
        if (ctx.processId() == null) {
            throw new ToolException("trillian_ask is only available inside a Trillian worker");
        }

        // The marker has to be set before the engine reaches its exit
        // branch — it is the only thing distinguishing this exit from a
        // finished one.
        thinkProcessService.setEngineParamOverride(
                ctx.processId(), TrillianWorkerEngine.PARAM_ASK_PENDING, true);

        // Persisted as an assistant message for the same reason
        // trillian_done persists its summary: that is what
        // ParentNotificationListener.enrichWithLastReply reads, and it is
        // how the question reaches Trillian-User.
        try {
            ThinkProcessDocument process =
                    thinkProcessService.findById(ctx.processId()).orElse(null);
            if (process != null) {
                chatMessageService.append(ChatMessageDocument.builder()
                        .tenantId(process.getTenantId())
                        .sessionId(process.getSessionId())
                        .thinkProcessId(process.getId())
                        .role(ChatRole.ASSISTANT)
                        .content(text)
                        .build());
            }
        } catch (RuntimeException e) {
            log.warn("trillian_ask: could not persist the question of process='{}': {}",
                    ctx.processId(), e.toString());
        }
        log.info("Trillian worker id='{}' asks: {}", ctx.processId(),
                text.length() > 120 ? text.substring(0, 120) + "…" : text);

        Map<String, Object> out = new LinkedHashMap<>();
        // Same exit as trillian_done — leave the loop. The engine decides
        // that this one parks rather than closes.
        out.put(FrankieTermination.RESULT_TERMINATE_KEY, true);
        out.put("status", "asked");
        out.put("note", "You are now waiting. The answer will arrive as a new "
                + "message and you continue from here — do not repeat the work.");
        return out;
    }
}
