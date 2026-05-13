package de.mhus.vance.brain.hooks;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.AiChatOptions;
import de.mhus.vance.brain.ai.AiModelResolver;
import de.mhus.vance.brain.ai.AiModelService;
import de.mhus.vance.brain.ai.ChatBehaviorBuilder;
import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.shared.settings.SettingService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * LLM-backed runner for {@code type: llm} hooks. Renders the
 * configured Pebble {@code prompt} against the event + context, asks
 * the resolved model for a JSON action list, validates each entry,
 * and executes the verbs against {@link HookHostApi}.
 *
 * <p>The LLM never emits free code — only the small declarative verb
 * set documented in {@code specification/hooks.md} §4.2 ({@code
 * http.post / http.put / http.get}, {@code inbox.create},
 * {@code log.info}). Unknown verbs or malformed entries fail the run
 * with {@code phase=actions}; the hook host-API is the single
 * execution surface, so the model can't escape the sandbox via the
 * action shape.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LlmHookRunner implements HookRunner {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final PromptTemplateRenderer templateRenderer;
    private final AiModelResolver modelResolver;
    private final AiModelService modelService;
    private final SettingService settingService;

    @Override
    public HookRunResult run(
            HookDef def,
            HookContext context,
            Map<String, @Nullable Object> eventPayload,
            HookHostApi hostApi) {
        if (def.prompt() == null || def.model() == null) {
            return HookRunResult.failed(Duration.ZERO,
                    "parse", "LLM hook missing prompt/model");
        }
        Instant start = Instant.now();
        long timeoutMs = def.timeout().toMillis();

        ExecutorService watchdog = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "vance-hook-llm");
            t.setDaemon(true);
            return t;
        });

        try {
            Future<Integer> future = watchdog.submit(() -> doRun(def, context, eventPayload, hostApi));
            try {
                int actionCount = future.get(timeoutMs, TimeUnit.MILLISECONDS);
                return HookRunResult.completed(
                        Duration.between(start, Instant.now()), actionCount);
            } catch (TimeoutException e) {
                future.cancel(true);
                return HookRunResult.failed(
                        Duration.between(start, Instant.now()),
                        "timeout",
                        "LLM hook timed out after " + timeoutMs + "ms");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                return HookRunResult.failed(
                        Duration.between(start, Instant.now()),
                        "cancelled",
                        "LLM hook interrupted");
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                String phase = cause instanceof HookActionException hae ? hae.phase : "llm";
                return HookRunResult.failed(
                        Duration.between(start, Instant.now()),
                        phase,
                        cause.getMessage() == null
                                ? cause.getClass().getSimpleName()
                                : cause.getMessage());
            }
        } finally {
            watchdog.shutdownNow();
        }
    }

    /** Returns the number of actions executed successfully. */
    private int doRun(
            HookDef def,
            HookContext context,
            Map<String, @Nullable Object> eventPayload,
            HookHostApi hostApi) {
        // 1. Render the prompt.
        Map<String, Object> templateVars = new LinkedHashMap<>();
        templateVars.put("event", eventPayload);
        templateVars.put("context", contextAsMap(context));
        templateVars.put("tenantId", context.tenantId());
        templateVars.put("projectId", context.projectId());
        templateVars.put("eventName", context.event().wireName());
        templateVars.put("hookName", context.hookName());
        String prompt;
        try {
            prompt = templateRenderer.render(def.prompt(), templateVars);
        } catch (RuntimeException ex) {
            throw new HookActionException("render", "prompt render failed: " + ex.getMessage(), ex);
        }
        if (prompt == null || prompt.isBlank()) {
            throw new HookActionException("render", "rendered prompt is empty");
        }

        // 2. Build the AiChat.
        AiChatConfig config;
        try {
            config = ChatBehaviorBuilder.resolveOne(
                    def.model(), context.tenantId(), context.projectId(),
                    /*processId*/ null, settingService, modelResolver);
        } catch (RuntimeException ex) {
            throw new HookActionException("model", ex.getMessage(), ex);
        }
        AiChatOptions options = AiChatOptions.builder()
                .tenantId(context.tenantId())
                .projectId(context.projectId())
                .maxTokens(def.maxTokens())
                .temperature(0.0)
                .timeoutSeconds((int) Math.max(1, def.timeout().toSeconds()))
                .build();
        AiChat chat;
        try {
            chat = modelService.createChat(config, options);
        } catch (RuntimeException ex) {
            throw new HookActionException("model", ex.getMessage(), ex);
        }

        // 3. Ask + parse.
        String response;
        try {
            response = chat.ask(prompt);
        } catch (RuntimeException ex) {
            throw new HookActionException("llm", ex.getMessage(), ex);
        }
        JsonNode root = parseActionsJson(response);
        JsonNode actions = root.get("actions");
        if (actions == null || actions.isNull()) {
            throw new HookActionException(
                    "actions", "LLM response is missing 'actions' array");
        }
        if (!actions.isArray()) {
            throw new HookActionException(
                    "actions", "'actions' must be a JSON array");
        }

        // 4. Execute verbs.
        int executed = 0;
        for (int i = 0; i < actions.size(); i++) {
            JsonNode action = actions.get(i);
            executeAction(i, action, hostApi);
            executed++;
        }
        return executed;
    }

    private static Map<String, Object> contextAsMap(HookContext ctx) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tenantId", ctx.tenantId());
        m.put("projectId", ctx.projectId());
        m.put("eventName", ctx.event().wireName());
        m.put("hookName", ctx.hookName());
        m.put("correlationId", ctx.correlationId());
        m.put("firedAt", ctx.firedAt() == null ? "" : ctx.firedAt().toString());
        return m;
    }

    private static JsonNode parseActionsJson(String response) {
        String trimmed = stripFences(response).trim();
        if (trimmed.isEmpty()) {
            throw new HookActionException("actions", "LLM response is empty");
        }
        try {
            JsonNode node = JSON.readTree(trimmed);
            if (!node.isObject()) {
                throw new HookActionException(
                        "actions", "LLM response is not a JSON object");
            }
            return node;
        } catch (RuntimeException ex) {
            throw new HookActionException(
                    "actions", "LLM response is not valid JSON: " + ex.getMessage(), ex);
        }
    }

    /** Strip optional ```json fences and trailing chatter around the JSON object. */
    private static String stripFences(String raw) {
        String s = raw.trim();
        if (s.startsWith("```")) {
            int firstNl = s.indexOf('\n');
            if (firstNl > 0) s = s.substring(firstNl + 1);
            int closing = s.lastIndexOf("```");
            if (closing >= 0) s = s.substring(0, closing);
        }
        // If the model still wrapped the JSON in prose, slice to the
        // first '{' / last '}' as a last resort.
        int open = s.indexOf('{');
        int close = s.lastIndexOf('}');
        if (open >= 0 && close > open) {
            s = s.substring(open, close + 1);
        }
        return s;
    }

    @SuppressWarnings("unchecked")
    private static void executeAction(int idx, JsonNode action, HookHostApi hostApi) {
        if (action == null || !action.isObject()) {
            throw new HookActionException(
                    "actions", "actions[" + idx + "] is not an object");
        }
        JsonNode kindNode = action.get("kind");
        if (kindNode == null || !kindNode.isTextual()) {
            throw new HookActionException(
                    "actions", "actions[" + idx + "].kind missing or not a string");
        }
        String kind = kindNode.asText().trim().toLowerCase(Locale.ROOT);
        Map<String, Object> raw = jsonObjectToMap(action);
        switch (kind) {
            case "http.post" -> hostApi.http.post(
                    requireString(raw, "url", idx),
                    raw.get("body"),
                    asMap(raw.get("options")));
            case "http.put" -> hostApi.http.put(
                    requireString(raw, "url", idx),
                    raw.get("body"),
                    asMap(raw.get("options")));
            case "http.get" -> hostApi.http.get(
                    requireString(raw, "url", idx),
                    asMap(raw.get("options")));
            case "inbox.create" -> {
                Map<String, Object> spec = new LinkedHashMap<>();
                for (Map.Entry<String, Object> e : raw.entrySet()) {
                    if ("kind".equals(e.getKey())) continue;
                    spec.put(e.getKey(), e.getValue());
                }
                hostApi.inbox.create(spec);
            }
            case "log.info" -> hostApi.log.info(
                    requireString(raw, "message", idx),
                    asMap(raw.get("data")));
            case "log.warn" -> hostApi.log.warn(
                    requireString(raw, "message", idx),
                    asMap(raw.get("data")));
            case "log.error" -> hostApi.log.error(
                    requireString(raw, "message", idx),
                    asMap(raw.get("data")));
            default -> throw new HookActionException(
                    "actions",
                    "actions[" + idx + "].kind '" + kindNode.asText()
                            + "' is not a known verb");
        }
    }

    private static String requireString(Map<String, Object> raw, String key, int idx) {
        Object v = raw.get(key);
        if (!(v instanceof String s) || s.isBlank()) {
            throw new HookActionException(
                    "actions",
                    "actions[" + idx + "]." + key + " is required");
        }
        return s;
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Map<String, Object> asMap(@Nullable Object o) {
        if (o == null) return null;
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        return null;
    }

    private static Map<String, Object> jsonObjectToMap(JsonNode obj) {
        try {
            return JSON.convertValue(obj, Map.class);
        } catch (RuntimeException ex) {
            return new LinkedHashMap<>();
        }
    }

    /** Internal carrier so the watchdog can attribute the failure phase. */
    static final class HookActionException extends RuntimeException {
        final String phase;
        HookActionException(String phase, String message) { super(message); this.phase = phase; }
        HookActionException(String phase, String message, Throwable cause) { super(message, cause); this.phase = phase; }
    }
}
