package de.mhus.vance.brain.tools.context;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Final user-facing reply marker for one engine turn.
 *
 * <h2>Engine contract</h2>
 *
 * <p>The LLM <em>must</em> call this tool exactly once per turn after
 * any other tool calls. The {@code message} arg is what the user sees
 * (sent through the regular chat-append + streaming pipeline);
 * {@code awaiting_user_input} tells the engine whether to go
 * {@link de.mhus.vance.api.thinkprocess.ThinkProcessStatus#BLOCKED} —
 * waiting on the user — or
 * {@link de.mhus.vance.api.thinkprocess.ThinkProcessStatus#IDLE} —
 * ready to auto-wake on the next pending message (e.g. a worker's
 * {@code ProcessEvent}).
 *
 * <h2>Server-side semantics</h2>
 *
 * <p>{@link #invoke} has no side effect. The engine layer (Ford,
 * Arthur via Ford-delegation, etc.) recognises a {@code respond}
 * tool-call as the final marker of the turn and consumes the args
 * directly from the {@code ToolExecutionRequest} — it doesn't go
 * through {@link de.mhus.vance.brain.tools.ToolDispatcher} for the
 * actual side-effect path. Calling {@code invoke} would only happen
 * if some non-engine code dispatched the tool generically; we return
 * an echo of the args so the caller can extract them, but that's not
 * the supported path.
 *
 * <p>See {@code specification/structured-engine-output.md}.
 */
@Component
public class RespondTool implements Tool {

    public static final String NAME = "respond";
    public static final String PARAM_MESSAGE = "message";
    public static final String PARAM_AWAITING_USER_INPUT = "awaiting_user_input";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    PARAM_MESSAGE, Map.of(
                            "type", "string",
                            "description", "User-facing reply text. "
                                    + "Markdown allowed. This becomes the assistant "
                                    + "chat-message for this turn."),
                    PARAM_AWAITING_USER_INPUT, Map.of(
                            "type", "boolean",
                            "description", "true (default) when you expect the "
                                    + "user to respond next — engine goes BLOCKED. "
                                    + "false when you've kicked off background work "
                                    + "(e.g. spawned a worker via process_create) and "
                                    + "do not need the user to react — engine goes "
                                    + "IDLE and auto-wakes on the worker's "
                                    + "ProcessEvent.")),
            "required", List.of(PARAM_MESSAGE));

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Final user-facing reply for this turn. Call this exactly once "
                + "per turn after any other tool calls. Set "
                + "awaiting_user_input=false when the engine has spawned async "
                + "work and does not need the user to react.";
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
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        // No side effect — engines short-circuit the call from inside the
        // tool-loop and never get here. This implementation exists for the
        // {@code ToolDispatcher} contract; it just echoes the args back so
        // a non-engine caller can extract them.
        Map<String, Object> out = new LinkedHashMap<>();
        if (params != null) {
            Object msg = params.get(PARAM_MESSAGE);
            if (msg != null) out.put(PARAM_MESSAGE, msg);
            Object awaiting = params.get(PARAM_AWAITING_USER_INPUT);
            out.put(PARAM_AWAITING_USER_INPUT, awaiting == null ? Boolean.TRUE : awaiting);
        } else {
            out.put(PARAM_AWAITING_USER_INPUT, Boolean.TRUE);
        }
        return out;
    }
}
