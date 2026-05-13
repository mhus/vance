package de.mhus.vance.brain.jeltz;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.EngineChatFactory;
import de.mhus.vance.brain.thinkengine.EnginePromptResolver;
import de.mhus.vance.brain.thinkengine.ParentReport;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.SystemPrompts;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.util.JsonSchemaLight;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Jeltz — single-shot structured-output engine.
 *
 * <p>Reads {@code prompt} and {@code schema} from the process's
 * {@code engineParams}, calls the configured LLM, validates the JSON
 * reply against the schema via {@link JsonSchemaLight}, retries on
 * violation up to {@code maxAttempts}, then persists a final
 * {@code assistant} chat message containing a {@link #writeSuccessResult
 * success} or {@link #writeErrorResult error} wrapper and closes the
 * process with {@link CloseReason#DONE}.
 *
 * <p>All work happens inside {@link #start} — the engine has no chat
 * loop, no tool use, no inbox handling, and no streaming. The lane
 * runs Jeltz exactly once.
 *
 * <p>Spec: {@code specification/jeltz-engine.md}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JeltzEngine implements ThinkEngine {

    public static final String NAME = "jeltz";
    public static final String VERSION = "0.1.0";

    private static final String DEFAULT_PROMPT_PATH = "prompts/jeltz-prompt.md";

    /**
     * Hardcoded fallback for the absolute minimum — overridden by the
     * bundled {@code prompts/jeltz-prompt.md} which the document cascade
     * surfaces in practice.
     */
    private static final String FALLBACK_PROMPT =
            "You are Jeltz. Answer the user's request with a single JSON "
                    + "object that conforms to the schema below. No prose, "
                    + "no markdown fences, no commentary outside the JSON.";

    /** Recipe-tunable; clamped to {@link #MAX_ATTEMPTS_HARD_CAP} on read. */
    private static final int DEFAULT_MAX_ATTEMPTS = 3;

    /** Defence against recipe authors typing {@code maxAttempts: 9999}. */
    private static final int MAX_ATTEMPTS_HARD_CAP = 10;

    private final ThinkProcessService thinkProcessService;
    private final ObjectMapper objectMapper;
    private final EngineChatFactory engineChatFactory;
    private final EnginePromptResolver enginePromptResolver;

    // ──────────────────── Metadata ────────────────────

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String title() {
        return "Jeltz (Structured Output)";
    }

    @Override
    public String description() {
        return "Single-shot engine that returns one schema-validated JSON object.";
    }

    @Override
    public String version() {
        return VERSION;
    }

    // ──────────────────── Lifecycle ────────────────────

    @Override
    public void start(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.info("Jeltz.start tenant='{}' session='{}' id='{}'",
                process.getTenantId(), process.getSessionId(), process.getId());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.RUNNING);
        try {
            runStructuredQuery(process, ctx);
        } catch (RuntimeException e) {
            // Last-resort: never leave the process hanging in RUNNING.
            // The result write inside runStructuredQuery already covers
            // expected failure modes (llm_error, invalid_params, …);
            // this catches the truly unexpected.
            log.error("Jeltz id='{}' unhandled failure: {}",
                    process.getId(), e.toString(), e);
            writeErrorResult(process, ctx, "internal_error",
                    String.valueOf(e.getMessage()), 0, null);
        } finally {
            // Always close DONE — even error outcomes carry a meaningful
            // wrapper that the caller can inspect. STOPPED only happens
            // through stop().
            if (process.getStatus() != ThinkProcessStatus.CLOSED) {
                thinkProcessService.closeProcess(process.getId(), CloseReason.DONE);
            }
        }
    }

    @Override
    public void resume(ThinkProcessDocument process, ThinkEngineContext ctx) {
        if (process.getStatus() == ThinkProcessStatus.CLOSED) {
            log.debug("Jeltz.resume id='{}' already CLOSED — no-op", process.getId());
            return;
        }
        // A mid-run brain restart left this Jeltz unfinished. The work is
        // idempotent against the original params: rerun from scratch. The
        // chat-log gets a second pair of user/assistant entries — acceptable
        // for an audit trail.
        log.info("Jeltz.resume id='{}' rerunning structured-query", process.getId());
        start(process, ctx);
    }

    @Override
    public void suspend(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.debug("Jeltz.suspend id='{}'", process.getId());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.SUSPENDED);
    }

    @Override
    public void steer(ThinkProcessDocument process, ThinkEngineContext ctx, SteerMessage message) {
        log.warn("Jeltz.steer id='{}' got unexpected '{}' — Jeltz is single-shot, discarding",
                process.getId(), message.getClass().getSimpleName());
    }

    @Override
    public void runTurn(ThinkProcessDocument process, ThinkEngineContext ctx) {
        // Drain to keep the pending queue clean; Jeltz does all its work
        // in start() and never expects a post-spawn turn.
        List<SteerMessage> drained = ctx.drainPending();
        if (!drained.isEmpty()) {
            log.warn("Jeltz.runTurn id='{}' discarded {} pending message(s) — Jeltz is single-shot",
                    process.getId(), drained.size());
        }
    }

    @Override
    public void stop(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.info("Jeltz.stop id='{}'", process.getId());
        if (process.getStatus() != ThinkProcessStatus.CLOSED) {
            writeErrorResult(
                    process, ctx, "stopped",
                    "Process stopped by user/parent before completion.", 0, null);
            thinkProcessService.closeProcess(process.getId(), CloseReason.STOPPED);
        }
    }

    @Override
    public ParentReport summarizeForParent(
            ThinkProcessDocument process, ProcessEventType eventType) {
        // The parent reads the actual JSON wrapper out of the chat log; here
        // we only need a one-liner that the parent's LLM can see.
        return ParentReport.of(
                "Jeltz process " + process.getId() + " "
                        + eventType.name().toLowerCase()
                        + " — see chat log for structured result wrapper.");
    }

    // ──────────────────── Core ────────────────────

    private void runStructuredQuery(
            ThinkProcessDocument process, ThinkEngineContext ctx) {
        // ── Params extraction ──────────────────────────────────────────
        String prompt = paramString(process, "prompt", null);
        Object schemaRaw = paramObject(process, "schema");
        int maxAttempts = clampAttempts(paramInt(
                process, "maxAttempts", DEFAULT_MAX_ATTEMPTS));

        if (prompt == null || prompt.isBlank()) {
            writeErrorResult(process, ctx, "invalid_params",
                    "Missing or blank engineParams.prompt.", 0, null);
            return;
        }
        if (!(schemaRaw instanceof Map<?, ?> schemaMapRaw)) {
            writeErrorResult(process, ctx, "invalid_params",
                    "Missing or non-object engineParams.schema.", 0, null);
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) schemaMapRaw;
        Object typeRaw = schema.get("type");
        if (!(typeRaw instanceof String typeStr)
                || !"object".equalsIgnoreCase(typeStr.trim())) {
            writeErrorResult(process, ctx, "invalid_schema",
                    "Top-level schema must declare type: object (got " + typeRaw + ").",
                    0, null);
            return;
        }

        // ── Audit trail: synthetic user message ────────────────────────
        ChatMessageService chatLog = ctx.chatMessageService();
        chatLog.append(ChatMessageDocument.builder()
                .tenantId(process.getTenantId())
                .sessionId(process.getSessionId())
                .thinkProcessId(process.getId())
                .role(ChatRole.USER)
                .content(prompt)
                .build());

        // ── Prompt assembly ────────────────────────────────────────────
        String basePath = paramString(process, "promptDocument", DEFAULT_PROMPT_PATH);
        String enginePrompt = enginePromptResolver.resolve(
                process, basePath, FALLBACK_PROMPT);
        // Legacy compose without Pebble — Jeltz has no tier-/model-aware
        // template variants. Recipe promptPrefix lands on
        // process.promptOverride and gets appended here.
        String basePrompt = SystemPrompts.compose(process, enginePrompt);
        String schemaJson = renderSchemaJson(schema);
        String systemPrompt = basePrompt
                + "\n\n## Required JSON schema\n\n"
                + "```json\n" + schemaJson + "\n```\n\n"
                + "Respond with exactly one JSON object that conforms to the "
                + "schema above. No prose before or after, no markdown fences.";

        // ── Chat client ────────────────────────────────────────────────
        EngineChatFactory.EngineChatBundle bundle =
                engineChatFactory.forProcess(process, ctx, NAME);
        AiChat aiChat = bundle.chat();
        ChatModel chatModel = aiChat.chatModel();

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt));
        messages.add(UserMessage.from(prompt));

        // ── Validator loop ─────────────────────────────────────────────
        String lastError = null;
        @Nullable Object lastInvalid = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            ChatResponse response;
            try {
                response = chatModel.chat(
                        ChatRequest.builder().messages(messages).build());
            } catch (RuntimeException e) {
                log.warn("Jeltz id='{}' attempt {}/{} LLM call failed: {}",
                        process.getId(), attempt, maxAttempts, e.toString());
                writeErrorResult(process, ctx, "llm_error",
                        String.valueOf(e.getMessage()), attempt, lastInvalid);
                return;
            }
            AiMessage reply = response.aiMessage();
            String text = reply == null || reply.text() == null ? "" : reply.text();
            // Keep the reply in the conversation so the next correction
            // round sees what the model actually said.
            if (reply != null) messages.add(reply);

            String json = extractJson(text);
            Object parsed;
            try {
                parsed = objectMapper.readValue(json, Object.class);
            } catch (RuntimeException e) {
                lastError = "reply is not valid JSON: " + e.getMessage();
                lastInvalid = text;
                log.info("Jeltz id='{}' attempt {}/{} non-JSON reply ({} chars): {}",
                        process.getId(), attempt, maxAttempts, text.length(), e.getMessage());
                appendCorrection(messages, lastError);
                continue;
            }
            if (!(parsed instanceof Map<?, ?>)) {
                lastError = "reply must be a JSON object, got "
                        + (parsed == null ? "null" : parsed.getClass().getSimpleName());
                lastInvalid = parsed;
                log.info("Jeltz id='{}' attempt {}/{} reply not an object",
                        process.getId(), attempt, maxAttempts);
                appendCorrection(messages, lastError);
                continue;
            }
            JsonSchemaLight.Result vr = JsonSchemaLight.validate(parsed, schema);
            if (vr.valid()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) parsed;
                writeSuccessResult(process, ctx, attempt, data);
                return;
            }
            lastError = vr.errorsJoined();
            lastInvalid = parsed;
            log.info("Jeltz id='{}' attempt {}/{} schema violation: {}",
                    process.getId(), attempt, maxAttempts, lastError);
            appendCorrection(messages, "Schema validation failed: " + lastError);
        }

        writeErrorResult(process, ctx, "max_attempts_exceeded",
                "Schema not satisfied after " + maxAttempts
                        + " attempt(s); last error: "
                        + (lastError == null ? "(none)" : lastError),
                maxAttempts, lastInvalid);
    }

    // ──────────────────── Result writers ──────────────────────────────

    private void writeSuccessResult(
            ThinkProcessDocument process, ThinkEngineContext ctx,
            int attempts, Map<String, Object> data) {
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("success", true);
        wrapper.put("attempts", attempts);
        wrapper.put("data", data);
        appendAssistantWrapper(process, ctx, wrapper);
    }

    private void writeErrorResult(
            ThinkProcessDocument process, ThinkEngineContext ctx,
            String error, @Nullable String message,
            int attempts, @Nullable Object lastInvalid) {
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("success", false);
        wrapper.put("attempts", attempts);
        wrapper.put("error", error);
        wrapper.put("message", message == null ? "" : message);
        if (lastInvalid != null) {
            wrapper.put("lastInvalid", lastInvalid);
        }
        appendAssistantWrapper(process, ctx, wrapper);
    }

    private void appendAssistantWrapper(
            ThinkProcessDocument process, ThinkEngineContext ctx,
            Map<String, Object> wrapper) {
        String json;
        try {
            json = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(wrapper);
        } catch (RuntimeException e) {
            // Fallback: serialise just success/error/message so the caller
            // still sees a coherent wrapper. ObjectMapper failure on a
            // LinkedHashMap with primitives is extremely rare.
            log.warn("Jeltz id='{}' result serialisation failed ({}); falling back to minimal wrapper",
                    process.getId(), e.toString());
            json = "{\"success\":" + wrapper.get("success")
                    + ",\"error\":\"serialisation_failed\""
                    + ",\"message\":\"" + e.getMessage() + "\"}";
        }
        ctx.chatMessageService().append(ChatMessageDocument.builder()
                .tenantId(process.getTenantId())
                .sessionId(process.getSessionId())
                .thinkProcessId(process.getId())
                .role(ChatRole.ASSISTANT)
                .content(json)
                .build());
    }

    // ──────────────────── Helpers ─────────────────────────────────────

    /**
     * Strips common LLM-output decorations (markdown fences,
     * leading/trailing prose) and returns the most-likely-JSON substring.
     * Best-effort — caller still tries to parse and falls into the
     * correction loop on failure.
     *
     * <p>Package-private + static for unit tests.
     */
    static String extractJson(@Nullable String text) {
        if (text == null) return "";
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return "";
        // Strip ```json ... ``` or ``` ... ``` fences.
        if (trimmed.startsWith("```")) {
            int firstNl = trimmed.indexOf('\n');
            if (firstNl > 0) {
                String body = trimmed.substring(firstNl + 1);
                int endFence = body.lastIndexOf("```");
                if (endFence > 0) {
                    return body.substring(0, endFence).trim();
                }
            }
        }
        // Otherwise grab the outermost JSON object.
        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1);
        }
        return trimmed;
    }

    private static void appendCorrection(List<ChatMessage> messages, String errorText) {
        messages.add(UserMessage.from(
                "Your previous reply did not match the required schema:\n\n"
                        + errorText
                        + "\n\nReturn one JSON object that conforms exactly. "
                        + "No prose, no markdown fences."));
    }

    private String renderSchemaJson(Map<String, Object> schema) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(schema);
        } catch (RuntimeException e) {
            // Pretty-print can fail on unusual key types; non-pretty is
            // always safe for primitive-keyed maps.
            try {
                return objectMapper.writeValueAsString(schema);
            } catch (RuntimeException e2) {
                return String.valueOf(schema);
            }
        }
    }

    static int clampAttempts(int requested) {
        if (requested < 1) return 1;
        return Math.min(requested, MAX_ATTEMPTS_HARD_CAP);
    }

    private static @Nullable Object paramObject(
            ThinkProcessDocument process, String key) {
        Map<String, Object> p = process.getEngineParams();
        return p == null ? null : p.get(key);
    }

    private static @Nullable String paramString(
            ThinkProcessDocument process, String key, @Nullable String fallback) {
        Object v = paramObject(process, key);
        return v instanceof String s && !s.isBlank() ? s : fallback;
    }

    private static int paramInt(
            ThinkProcessDocument process, String key, int fallback) {
        Object v = paramObject(process, key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s.trim()); }
            catch (NumberFormatException e) { return fallback; }
        }
        return fallback;
    }
}
