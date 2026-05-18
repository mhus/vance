package de.mhus.vance.brain.deepthought;

import de.mhus.vance.api.deepthought.DeepThoughtState;
import de.mhus.vance.api.deepthought.DeepThoughtState.ValidationError;
import de.mhus.vance.api.deepthought.DeepThoughtStatus;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.ai.EngineChatFactory;
import de.mhus.vance.brain.progress.LlmCallTracker;
import de.mhus.vance.brain.prompt.PromptContextBuilder;
import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.brain.script.JsValidationService;
import de.mhus.vance.brain.thinkengine.EnginePromptResolver;
import de.mhus.vance.brain.thinkengine.ParentReport;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Deep Thought — script-architect engine. Reads a goal, drafts a
 * JavaScript body, validates it parse-only, recovers on syntax errors
 * up to {@link DeepThoughtState#getMaxRecoveries()} times, and (optionally)
 * hands off to a Script Cortex runner.
 *
 * <p>The accepted script lives in {@code DeepThoughtState.generatedCode}
 * — there is no separate persistence to a project document. Debug
 * inspection happens through the {@code engineParams.deepThoughtState}
 * blob itself (Mongo find / Insights view); parents read the final
 * code through {@code summarizeForParent}.
 *
 * <p>State persists on
 * {@code ThinkProcessDocument.engineParams.deepThoughtState}; each
 * {@code runTurn} performs <em>one</em> phase, then yields and
 * schedules the next turn — same lane-discipline as Zaphod and
 * Vogon. The state machine:
 *
 * <pre>
 *   READY → DRAFTING → VALIDATING → DONE
 *                  ↑       │
 *                  └───────┘  (recovery loop: syntax error → re-draft
 *                             with error hint, up to maxRecoveries)
 *                                  │
 *                                  └→ EXECUTING → DONE (if executeOnDone)
 * </pre>
 *
 * <p>Phase 0 implementation: phase methods are stubbed so the
 * lifecycle round-trips; real DRAFTING/VALIDATING logic
 * lands in the follow-up task (see {@code planning/deepthought-engine.md}).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DeepThoughtEngine implements ThinkEngine {

    public static final String NAME = "deepthought";
    public static final String VERSION = "0.1.0";

    /** Set on {@code engineParams[STATE_KEY]} as the persisted
     *  {@link DeepThoughtState} for this process. */
    public static final String STATE_KEY = "deepThoughtState";

    /** {@code engineParams[GOAL_KEY]} — optional override; falls back
     *  to {@code process.getGoal()}. */
    public static final String GOAL_KEY = "goal";

    /** {@code engineParams[EXECUTE_ON_DONE_KEY]} — boolean; default false. */
    public static final String EXECUTE_ON_DONE_KEY = "executeOnDone";

    /** {@code engineParams[MAX_RECOVERIES_KEY]} — int; default 5. */
    public static final String MAX_RECOVERIES_KEY = "maxRecoveries";

    /** {@code engineParams[SCRIPT_ALLOWED_TOOLS_KEY]} — list of tool
     *  names the <em>generated script</em> will be permitted to call
     *  via {@code vance.tools.call(name, args)}. Used at DRAFTING time
     *  to render a concrete tool inventory (name + description +
     *  declared params) into the system prompt so the LLM can pick
     *  real tool names rather than hallucinate them. {@code null} or
     *  empty → no tool block rendered; the LLM is told the script must
     *  not call any tools. */
    public static final String SCRIPT_ALLOWED_TOOLS_KEY = "scriptAllowedTools";

    /** Cascade path for the Deep Thought DRAFTING system prompt.
     *  Loaded via {@link EnginePromptResolver} — project / _vance /
     *  bundled — and rendered as a Pebble template with {@code goal}
     *  injected as a variable. */
    private static final String DRAFTING_PROMPT_PATH = "prompts/deepthought-drafting.md";

    /** Last-resort Java fallback if the bundled prompt resource is
     *  missing (e.g. dev hot-reload without test resources). Real
     *  source-of-truth is {@link #DRAFTING_PROMPT_PATH}. */
    private static final String DRAFTING_FALLBACK_PROMPT =
            "You are the DRAFTING node of the Deep Thought engine. "
                    + "Reply with EXACTLY one ```javascript fenced block "
                    + "containing an IIFE that fulfils the goal: {{ goal }}";

    /** Matches the first ```javascript / ```js / ``` fenced block
     *  in the LLM reply. DOTALL so {@code .} spans line breaks. */
    private static final Pattern JS_FENCE = Pattern.compile(
            "```(?:javascript|js)?\\s*\\R([\\s\\S]*?)\\R```",
            Pattern.MULTILINE);

    private final ThinkProcessService thinkProcessService;
    private final ProcessEventEmitter eventEmitter;
    private final ObjectMapper objectMapper;
    private final EngineChatFactory engineChatFactory;
    private final EnginePromptResolver enginePromptResolver;
    private final PromptTemplateRenderer promptTemplateRenderer;
    private final LlmCallTracker llmCallTracker;
    private final JsValidationService jsValidationService;
    private final ToolDispatcher toolDispatcher;

    // ──────────────────── Metadata ────────────────────

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String title() {
        return "Deep Thought (Script Architect)";
    }

    @Override
    public String description() {
        return "Generates JavaScript orchestrator scripts from a high-level "
                + "goal. Drafts via LLM, validates parse-only, recovers on "
                + "syntax errors, persists via doc_write_text.";
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public Set<String> allowedTools() {
        // v1: none. DRAFTING uses a direct LLM call (no tool_use),
        // VALIDATING is in-process via JsValidationService, and no
        // document is written. EXECUTING (v1.1) will widen this to
        // process_run / execute_javascript when Script Cortex lands.
        return Set.of();
    }

    @Override
    public boolean asyncSteer() {
        return true;
    }

    // ──────────────────── Lifecycle ────────────────────

    @Override
    public void start(ThinkProcessDocument process, ThinkEngineContext ctx) {
        DeepThoughtState state = buildInitialState(process);
        persistState(process, state);
        log.info("DeepThought.start tenant='{}' session='{}' id='{}' "
                        + "executeOnDone={} maxRecoveries={}",
                process.getTenantId(), process.getSessionId(), process.getId(),
                state.isExecuteOnDone(), state.getMaxRecoveries());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
        eventEmitter.scheduleTurn(process.getId());
    }

    @Override
    public void resume(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.debug("DeepThought.resume id='{}'", process.getId());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
        eventEmitter.scheduleTurn(process.getId());
    }

    @Override
    public void suspend(ThinkProcessDocument process, ThinkEngineContext ctx) {
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.SUSPENDED);
    }

    @Override
    public void steer(ThinkProcessDocument process, ThinkEngineContext ctx,
            SteerMessage message) {
        // v1: no live-edit — steer just nudges the lane to run the
        // next turn. Inbox-answers (e.g. clarifying a goal) would
        // land here once the FRAMING phase ships.
        eventEmitter.scheduleTurn(process.getId());
    }

    @Override
    public void stop(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.info("DeepThought.stop id='{}'", process.getId());
        thinkProcessService.closeProcess(process.getId(), CloseReason.STOPPED);
    }

    // ──────────────────── runTurn ────────────────────

    @Override
    public void runTurn(ThinkProcessDocument process, ThinkEngineContext ctx) {
        DeepThoughtState state = loadState(process);

        // Terminal-status short-circuit (mirrors Zaphod's pattern —
        // queued runTurns must not re-fire DONE/FAILED transitions).
        if (state.getStatus() == DeepThoughtStatus.DONE) {
            thinkProcessService.closeProcess(process.getId(), CloseReason.DONE);
            return;
        }
        if (state.getStatus() == DeepThoughtStatus.FAILED) {
            thinkProcessService.closeProcess(process.getId(), CloseReason.STALE);
            return;
        }

        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.RUNNING);
        try {
            // Drain pending — v1 ignores; future FRAMING phase will
            // consume inbox-answers here.
            for (SteerMessage ignored : ctx.drainPending()) {
                // discard
            }

            DeepThoughtStatus next = dispatch(process, ctx, state);
            state.setStatus(next);
            persistState(process, state);

            if (next == DeepThoughtStatus.DONE) {
                thinkProcessService.closeProcess(process.getId(), CloseReason.DONE);
            } else if (next == DeepThoughtStatus.FAILED) {
                thinkProcessService.closeProcess(process.getId(), CloseReason.STALE);
            } else {
                eventEmitter.scheduleTurn(process.getId());
                thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
            }
        } catch (RuntimeException e) {
            log.warn("DeepThought runTurn failed id='{}': {}",
                    process.getId(), e.toString(), e);
            state.setStatus(DeepThoughtStatus.FAILED);
            state.setFailureReason("runTurn threw: " + e.getMessage());
            persistState(process, state);
            thinkProcessService.closeProcess(process.getId(), CloseReason.STALE);
            throw e;
        }
    }

    /**
     * Single-step state machine — picks the next phase based on the
     * current status. Phase methods are responsible for mutating
     * {@code state}; this method returns the next status only.
     */
    private DeepThoughtStatus dispatch(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            DeepThoughtState state) {
        return switch (state.getStatus()) {
            case READY -> DeepThoughtStatus.DRAFTING;
            case DRAFTING -> runDrafting(process, ctx, state);
            case VALIDATING -> runValidating(process, ctx, state);
            case EXECUTING -> runExecuting(process, ctx, state);
            // DONE/FAILED handled before dispatch; defensive fall-through.
            case DONE -> DeepThoughtStatus.DONE;
            case FAILED -> DeepThoughtStatus.FAILED;
        };
    }

    // ──────────────────── Phases ────────────────────

    /**
     * DRAFTING — one LLM call that produces a {@code ```javascript}
     * fenced body. The system prompt comes from the cascade path
     * {@link #DRAFTING_PROMPT_PATH} (Pebble-rendered with the goal);
     * the user message carries the goal verbatim plus, on a recovery
     * attempt, the previous draft and the validation errors that
     * killed it.
     *
     * <p>Always transitions to VALIDATING. A reply without a parseable
     * fence is stored verbatim as {@code generatedCode} and recorded as
     * a synthetic validation error — the regular recovery loop then
     * kicks in (so a malformed reply costs one recovery slot, same as
     * a syntax error).
     */
    DeepThoughtStatus runDrafting(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            DeepThoughtState state) {
        EngineChatFactory.EngineChatBundle bundle =
                engineChatFactory.forProcess(process, ctx, NAME);
        String modelAlias = bundle.primaryConfig().provider()
                + ":" + bundle.primaryConfig().modelName();

        // System prompt: cascade-resolved + Pebble-rendered with goal
        // and an optional tool inventory. The inventory is the highest
        // quality lever on generated scripts — without it the LLM
        // routinely hallucinates tool names ("file_write" instead of
        // "doc_write_text"); the validator can't catch that because
        // such errors only surface at run time.
        String basePath = paramString(process, "promptDocument", DRAFTING_PROMPT_PATH);
        String systemTpl = enginePromptResolver.resolve(
                process, basePath, DRAFTING_FALLBACK_PROMPT);
        Map<String, Object> ctxMap = new LinkedHashMap<>(
                PromptContextBuilder.forProcess(process, null)
                        .engine(NAME)
                        .build());
        ctxMap.put("goal", state.getGoal() == null ? "" : state.getGoal());
        ctxMap.put("toolInventory", renderToolInventory(process, ctx));
        String renderedSystem = promptTemplateRenderer.render(systemTpl, ctxMap);

        // User message: recovery hint first when applicable, then the
        // draft-now instruction. The header banner is large on purpose
        // — easy for the LLM to miss subtle "fix this" text buried mid-
        // prompt, especially after a long system message.
        String userMessage = buildDraftingUserMessage(state);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(renderedSystem == null ? "" : renderedSystem));
        messages.add(UserMessage.from(userMessage));

        long startMs = System.currentTimeMillis();
        ChatRequest request = ChatRequest.builder().messages(messages).build();
        ChatResponse response = bundle.chat().chatModel().chat(request);
        llmCallTracker.record(process, request, response,
                System.currentTimeMillis() - startMs, modelAlias);

        String reply = response.aiMessage() == null
                ? null : response.aiMessage().text();
        String body = extractJsBody(reply == null ? "" : reply);
        if (body == null || body.isBlank()) {
            // No fence — keep the raw reply for inspection and queue a
            // synthetic validation error so VALIDATING accounts the
            // attempt against the recovery budget.
            log.warn("DeepThought.runDrafting id='{}' reply had no parseable "
                            + "```javascript fence (reply chars={})",
                    process.getId(), reply == null ? 0 : reply.length());
            state.setGeneratedCode(reply == null ? "" : reply);
            List<ValidationError> errs = new ArrayList<>();
            errs.add(ValidationError.builder()
                    .sourceName("draft.js")
                    .line(0).column(0)
                    .message("LLM reply contained no ```javascript fenced "
                            + "block — re-emit with proper fences.")
                    .build());
            state.setValidationErrors(errs);
            return DeepThoughtStatus.VALIDATING;
        }

        state.setGeneratedCode(body);
        state.getValidationErrors().clear();
        log.info("DeepThought.runDrafting id='{}' attempt {} drafted {} chars",
                process.getId(), state.getRecoveryCount() + 1, body.length());
        return DeepThoughtStatus.VALIDATING;
    }

    /**
     * VALIDATING — parse-only check via {@link JsValidationService}.
     * On success → DONE (or EXECUTING when {@code executeOnDone}). On
     * failure → increment {@code recoveryCount}; back to DRAFTING with
     * the errors copied into the state, or FAILED once
     * {@code recoveryCount >= maxRecoveries}.
     */
    DeepThoughtStatus runValidating(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            DeepThoughtState state) {
        String code = state.getGeneratedCode();
        JsValidationService.JsValidationResult result =
                jsValidationService.validate(code, "draft.js");
        if (result.ok()) {
            state.getValidationErrors().clear();
            log.info("DeepThought.runValidating id='{}' OK after {} recovery attempt(s)",
                    process.getId(), state.getRecoveryCount());
            return state.isExecuteOnDone()
                    ? DeepThoughtStatus.EXECUTING
                    : DeepThoughtStatus.DONE;
        }

        // Translate JsValidationError → API ValidationError so the
        // state can serialize over the wire.
        List<ValidationError> errors = new ArrayList<>();
        for (JsValidationService.JsValidationError e : result.errors()) {
            errors.add(ValidationError.builder()
                    .sourceName(e.sourceName())
                    .line(e.line())
                    .column(e.column())
                    .message(e.message())
                    .build());
        }
        state.setValidationErrors(errors);
        state.setRecoveryCount(state.getRecoveryCount() + 1);
        log.info("DeepThought.runValidating id='{}' FAIL — attempt {}/{}, "
                        + "errors: {}",
                process.getId(), state.getRecoveryCount(),
                state.getMaxRecoveries(),
                errors.isEmpty() ? "?" : errors.get(0).getMessage());

        if (state.getRecoveryCount() >= state.getMaxRecoveries()) {
            state.setFailureReason("Exceeded maxRecoveries ("
                    + state.getMaxRecoveries()
                    + ") — last error: "
                    + (errors.isEmpty() ? "(none)" : errors.get(0).getMessage()));
            return DeepThoughtStatus.FAILED;
        }
        return DeepThoughtStatus.DRAFTING;
    }

    DeepThoughtStatus runExecuting(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            DeepThoughtState state) {
        log.debug("DeepThought.runExecuting STUB id='{}' codeLen={}",
                process.getId(),
                state.getGeneratedCode() == null ? 0 : state.getGeneratedCode().length());
        // v1 has no Script Cortex runner — go straight to DONE.
        return DeepThoughtStatus.DONE;
    }

    // ──────────────────── Phase helpers ────────────────────

    private static String buildDraftingUserMessage(DeepThoughtState state) {
        StringBuilder sb = new StringBuilder();
        if (state.getRecoveryCount() > 0 && !state.getValidationErrors().isEmpty()) {
            sb.append("================================================\n");
            sb.append("⚠  PREVIOUS DRAFT FAILED VALIDATION ⚠\n");
            sb.append("================================================\n\n");
            if (state.getGeneratedCode() != null && !state.getGeneratedCode().isBlank()) {
                sb.append("Previous draft:\n```javascript\n")
                        .append(state.getGeneratedCode())
                        .append("\n```\n\n");
            }
            sb.append("Errors that must be fixed:\n");
            for (ValidationError e : state.getValidationErrors()) {
                sb.append("  - ");
                if (e.getLine() > 0 || e.getColumn() > 0) {
                    sb.append("line ").append(e.getLine())
                            .append(", col ").append(e.getColumn())
                            .append(": ");
                }
                sb.append(e.getMessage() == null ? "(no message)" : e.getMessage())
                        .append('\n');
            }
            sb.append("\nRe-emit the COMPLETE corrected script. Keep the "
                    + "structure of the previous draft; only fix the listed "
                    + "errors.\n");
            sb.append("================================================\n\n");
        }
        sb.append("Goal:\n").append(state.getGoal() == null ? "" : state.getGoal());
        sb.append("\n\nReply ONLY with a single ```javascript fenced block. "
                + "No prose before or after.");
        return sb.toString();
    }

    /**
     * Pulls the first fenced JavaScript body out of an LLM reply.
     * Accepts ```javascript, ```js, or unmarked ``` fences (the prompt
     * asks for ```javascript but production LLMs sometimes pick the
     * shorter alias). Returns {@code null} when no fence is found —
     * caller treats that as a validation error.
     */
    static @Nullable String extractJsBody(@Nullable String reply) {
        if (reply == null || reply.isBlank()) return null;
        Matcher m = JS_FENCE.matcher(reply);
        if (!m.find()) return null;
        String body = m.group(1);
        return body == null || body.isBlank() ? null : body;
    }

    private static @Nullable String paramString(
            ThinkProcessDocument process, String key, @Nullable String fallback) {
        Map<String, Object> p = process.getEngineParams();
        Object v = p == null ? null : p.get(key);
        return v instanceof String s && !s.isBlank() ? s : fallback;
    }

    /**
     * Renders the {@code scriptAllowedTools} list as a Markdown
     * inventory the DRAFTING prompt embeds. Returns an empty string
     * when no tools are configured — the Pebble template uses
     * {@code {% if toolInventory %}…{% endif %}} so the section is
     * silently omitted in that case.
     *
     * <p>Format per entry: {@code - **name** — description.
     * Args: {required}, {optional}}. The full JSON-schema would be
     * too verbose for a single prompt; the LLM gets enough to pick
     * the right tool, then can re-check params at validate-time.
     */
    private String renderToolInventory(
            ThinkProcessDocument process, ThinkEngineContext ctx) {
        List<String> wanted = scriptAllowedTools(process);
        if (wanted.isEmpty()) return "";

        ToolInvocationContext scope = new ToolInvocationContext(
                process.getTenantId(),
                process.getProjectId(),
                process.getSessionId(),
                process.getId(),
                /*userId*/ null);

        StringBuilder sb = new StringBuilder();
        for (String name : wanted) {
            ToolDispatcher.Resolved resolved =
                    toolDispatcher.resolve(name, scope).orElse(null);
            if (resolved == null) {
                // Unknown tool name in scriptAllowedTools — render a
                // placeholder so the LLM at least sees the name; the
                // recovery loop can't help here (runtime issue), so we
                // leave detection to the caller's pre-check.
                sb.append("- **").append(name).append("** — ")
                        .append("(tool not registered in this scope)\n");
                continue;
            }
            Tool tool = resolved.tool();
            sb.append("- **").append(tool.name()).append("** — ")
                    .append(oneLine(tool.description())).append("\n");
            String args = describeParams(tool.paramsSchema());
            if (!args.isEmpty()) {
                sb.append("  ").append(args).append("\n");
            }
        }
        return sb.toString();
    }

    private static List<String> scriptAllowedTools(ThinkProcessDocument process) {
        Map<String, Object> p = process.getEngineParams();
        Object raw = p == null ? null : p.get(SCRIPT_ALLOWED_TOOLS_KEY);
        if (!(raw instanceof List<?> list) || list.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (Object entry : list) {
            if (entry instanceof String s && !s.isBlank()) out.add(s.trim());
        }
        return out;
    }

    /**
     * Compact "Args: ..." line built from the JSON-Schema-like
     * {@code paramsSchema}. We separate required and optional so the
     * LLM picks up the mandatory bits at a glance. Schema-less tools
     * (raw map without {@code properties}) yield an empty string —
     * the description alone must carry the contract.
     */
    @SuppressWarnings("unchecked")
    private static String describeParams(@Nullable Map<String, Object> schema) {
        if (schema == null) return "";
        Object propsRaw = schema.get("properties");
        if (!(propsRaw instanceof Map<?, ?> props) || props.isEmpty()) return "";
        Object reqRaw = schema.get("required");
        List<String> required = reqRaw instanceof List<?> rl
                ? rl.stream().filter(o -> o instanceof String)
                    .map(o -> (String) o).toList()
                : List.of();
        // Preserve the schema-declared order for required: that's the
        // author's intent and how docs typically read. Optional then
        // gets whatever's left in props order.
        List<String> requiredOut = new ArrayList<>(required);
        List<String> optionalOut = new ArrayList<>();
        for (Map.Entry<?, ?> e : props.entrySet()) {
            if (!(e.getKey() instanceof String key)) continue;
            if (required.contains(key)) continue;
            optionalOut.add(key);
        }
        StringBuilder sb = new StringBuilder();
        if (!requiredOut.isEmpty()) {
            sb.append("Required: ").append(String.join(", ", requiredOut)).append(".");
        }
        if (!optionalOut.isEmpty()) {
            if (sb.length() > 0) sb.append(" ");
            sb.append("Optional: ").append(String.join(", ", optionalOut)).append(".");
        }
        return sb.toString();
    }

    private static String oneLine(@Nullable String raw) {
        if (raw == null) return "";
        String s = raw.replaceAll("\\s+", " ").trim();
        return s.length() > 200 ? s.substring(0, 197) + "..." : s;
    }

    // ──────────────────── summarizeForParent ────────────────────

    @Override
    public ParentReport summarizeForParent(
            ThinkProcessDocument process, ProcessEventType eventType) {
        DeepThoughtState state;
        try {
            state = loadState(process);
        } catch (RuntimeException e) {
            return ParentReport.of("DeepThought process " + process.getId()
                    + " status=" + eventType.name().toLowerCase());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", eventType.name());
        payload.put("status", state.getStatus() == null
                ? null : state.getStatus().name());
        payload.put("codeLength", state.getGeneratedCode() == null
                ? 0 : state.getGeneratedCode().length());
        payload.put("recoveryCount", state.getRecoveryCount());
        payload.put("validationErrors", state.getValidationErrors().size());

        if (state.getStatus() == DeepThoughtStatus.DONE
                && state.getGeneratedCode() != null) {
            // Parent gets the script verbatim in a fenced block — its
            // own LLM (chat, Vogon, …) can quote it, run it through a
            // tool, or hand it on. The generatedCode also still sits
            // in engineParams for direct Mongo inspection.
            return new ParentReport(
                    "Deep Thought drafted a script (" + state.getGeneratedCode().length()
                            + " chars, " + state.getRecoveryCount()
                            + " recovery attempt(s)):\n\n```javascript\n"
                            + state.getGeneratedCode()
                            + "\n```\n",
                    payload);
        }
        if (state.getStatus() == DeepThoughtStatus.FAILED) {
            return new ParentReport(
                    "Deep Thought failed: "
                            + (state.getFailureReason() == null
                                    ? "unknown reason" : state.getFailureReason()),
                    payload);
        }
        return new ParentReport(
                "Deep Thought in progress — phase="
                        + (state.getStatus() == null ? "?" : state.getStatus().name())
                        + ", recoveries=" + state.getRecoveryCount(),
                payload);
    }

    // ──────────────────── State construction + persistence ────────────────────

    DeepThoughtState buildInitialState(ThinkProcessDocument process) {
        Map<String, Object> p = process.getEngineParams() == null
                ? new LinkedHashMap<>() : process.getEngineParams();

        String goal = optString(p.get(GOAL_KEY));
        if (goal == null) {
            goal = process.getGoal();
        }
        if (goal == null || goal.isBlank()) {
            throw new IllegalStateException(
                    "DeepThought.start requires a goal — neither "
                            + "engineParams.goal nor process.goal is set "
                            + "(id='" + process.getId() + "')");
        }

        boolean executeOnDone = parseBoolean(p.get(EXECUTE_ON_DONE_KEY), false);
        int maxRecoveries = parseInt(p.get(MAX_RECOVERIES_KEY), 5);
        if (maxRecoveries < 0) maxRecoveries = 0;

        return DeepThoughtState.builder()
                .goal(goal)
                .executeOnDone(executeOnDone)
                .maxRecoveries(maxRecoveries)
                .status(DeepThoughtStatus.READY)
                .build();
    }

    DeepThoughtState loadState(ThinkProcessDocument process) {
        Map<String, Object> p = process.getEngineParams();
        if (p == null) return DeepThoughtState.builder().build();
        Object raw = p.get(STATE_KEY);
        if (raw == null) return DeepThoughtState.builder().build();
        return objectMapper.convertValue(raw, DeepThoughtState.class);
    }

    @SuppressWarnings("unchecked")
    void persistState(ThinkProcessDocument process, DeepThoughtState state) {
        Map<String, Object> p = process.getEngineParams() == null
                ? new LinkedHashMap<>() : process.getEngineParams();
        Map<String, Object> serialized = objectMapper.convertValue(state, Map.class);
        p.put(STATE_KEY, serialized);
        process.setEngineParams(p);
        thinkProcessService.replaceEngineParams(process.getId(), p);
    }

    // ──────────────────── Helpers ────────────────────

    private static @Nullable String optString(@Nullable Object raw) {
        return raw instanceof String s && !s.isBlank() ? s : null;
    }

    private static boolean parseBoolean(@Nullable Object raw, boolean fallback) {
        if (raw instanceof Boolean b) return b;
        if (raw instanceof String s) return Boolean.parseBoolean(s.trim());
        return fallback;
    }

    private static int parseInt(@Nullable Object raw, int fallback) {
        if (raw instanceof Number n) return n.intValue();
        if (raw instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }
}
