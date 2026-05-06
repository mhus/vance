package de.mhus.vance.brain.ai.anthropic;

import de.mhus.vance.brain.ai.AiChatOptions;
import de.mhus.vance.brain.ai.CacheBoundary;
import de.mhus.vance.brain.ai.CacheTtl;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;

/**
 * Maps a langchain4j {@link ChatRequest} to the JSON body of the
 * Anthropic Messages API as a Java {@link Map}, then applies it to a
 * {@link MessageCreateParams.Builder} via
 * {@link MessageCreateParams.Builder#putAdditionalBodyProperty}.
 *
 * <p>The whole body is built as raw JSON (Maps, Lists, Strings) instead
 * of using the SDK's typed builders for {@code system} / {@code messages}
 * / {@code tools}. Reasons:
 *
 * <ul>
 *   <li><b>cache_control</b> (the whole point of this adapter) needs to
 *       sit on the last system block, the last tool, and optionally on
 *       message blocks — the typed builders for those classes vary
 *       between SDK minor versions, but the JSON shape is stable.</li>
 *   <li><b>Tool sortability</b> — we sort tools alphabetically before
 *       writing them to the JSON, so caching is bit-stable across calls
 *       with the same tool set.</li>
 *   <li><b>Future-proof</b> — new top-level fields (cache_control on
 *       message blocks, extended thinking blocks, etc.) need no SDK
 *       upgrade to flow through.</li>
 * </ul>
 *
 * <p>Top-level params that the SDK validates strictly ({@code model},
 * {@code max_tokens}, {@code temperature}) are still set via the typed
 * builder so the SDK's own bound checks apply.
 */
final class AnthropicRequestMapper {

    /** Reused for parsing tool-call argument JSON strings into Maps —
     *  Anthropic wants the parsed object on the {@code input} field. */
    private static final ObjectMapper JSON = new ObjectMapper();

    private AnthropicRequestMapper() {}

    /**
     * Apply the request to {@code builder}, including cache markers
     * derived from {@code options}. The builder must already have
     * {@code .model(...)} and {@code .maxTokens(...)} set by the caller
     * — this method only writes raw body fields ({@code system},
     * {@code messages}, {@code tools}).
     */
    static void apply(
            MessageCreateParams.Builder builder,
            ChatRequest request,
            AiChatOptions options) {
        @Nullable Double temperature = readTemperature(request, options);
        if (temperature != null) {
            builder.temperature(temperature);
        }

        Map<String, Object> body = buildBody(request, options);
        for (Map.Entry<String, Object> e : body.entrySet()) {
            builder.putAdditionalBodyProperty(e.getKey(), JsonValue.from(e.getValue()));
        }
    }

    private static Map<String, Object> buildBody(
            ChatRequest request, AiChatOptions options) {
        Map<String, Object> body = new LinkedHashMap<>();
        CacheBoundary boundary = effectiveBoundary(options);
        @Nullable String ttl = ttlString(options.getCacheTtl());

        // ─── System ───────────────────────────────────────────────
        @Nullable List<Map<String, Object>> system =
                buildSystemBlocks(request, boundary, ttl);
        if (system != null && !system.isEmpty()) {
            body.put("system", system);
        }

        // ─── Tools ─────────────────────────────────────────────────
        List<Map<String, Object>> tools = buildTools(request, boundary, ttl);
        if (!tools.isEmpty()) {
            body.put("tools", tools);
        }

        // ─── Messages ──────────────────────────────────────────────
        body.put("messages", buildMessages(request));
        return body;
    }

    private static @Nullable Double readTemperature(
            ChatRequest request, AiChatOptions options) {
        ChatRequestParameters params = request.parameters();
        if (params != null && params.temperature() != null) {
            return params.temperature();
        }
        return options.getTemperature();
    }

    /**
     * Lifts the request's {@link SystemMessage}s into Anthropic's
     * top-level {@code system} array (text-block form) so a
     * {@code cache_control} marker can be set on the last one. Returns
     * {@code null} when the request has no system messages — the
     * builder then omits the field entirely.
     */
    private static @Nullable List<Map<String, Object>> buildSystemBlocks(
            ChatRequest request, CacheBoundary boundary, @Nullable String ttl) {
        List<String> texts = new ArrayList<>();
        for (ChatMessage m : safeMessages(request)) {
            if (m instanceof SystemMessage s) {
                String t = s.text();
                if (t != null && !t.isEmpty()) {
                    texts.add(t);
                }
            }
        }
        if (texts.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> blocks = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("type", "text");
            block.put("text", texts.get(i));
            // Cache marker on the *last* system block — Anthropic
            // caches everything up to and including that block, so a
            // single marker covers any number of preceding blocks.
            boolean isLast = i == texts.size() - 1;
            if (isLast && boundary.cachesSystem()) {
                block.put("cache_control", cacheControl(ttl));
            }
            blocks.add(block);
        }
        return blocks;
    }

    private static List<Map<String, Object>> buildTools(
            ChatRequest request, CacheBoundary boundary, @Nullable String ttl) {
        List<ToolSpecification> raw = request.toolSpecifications();
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        // Stable order — caching needs bit-identical tool prefix
        // across calls. langchain4j hands us whatever the engine
        // assembled (often Map / Set ordering), which is unsafe.
        List<ToolSpecification> sorted = new ArrayList<>(raw);
        sorted.sort(Comparator.comparing(ToolSpecification::name));

        List<Map<String, Object>> out = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            ToolSpecification spec = sorted.get(i);
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", spec.name());
            if (spec.description() != null) {
                tool.put("description", spec.description());
            }
            tool.put("input_schema", toolInputSchema(spec));
            boolean isLast = i == sorted.size() - 1;
            if (isLast && boundary.cachesTools()) {
                tool.put("cache_control", cacheControl(ttl));
            }
            out.add(tool);
        }
        return out;
    }

    /**
     * Marker on the LAST tool covers every preceding tool definition
     * too, just like the system-block rule.
     */
    private static Map<String, Object> toolInputSchema(ToolSpecification spec) {
        if (spec.parameters() == null) {
            // Anthropic still wants an object schema even for no-param
            // tools. Empty properties is the documented form.
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("type", "object");
            empty.put("properties", Map.of());
            return empty;
        }
        return jsonSchema(spec.parameters());
    }

    private static List<Map<String, Object>> buildMessages(ChatRequest request) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ChatMessage m : safeMessages(request)) {
            if (m instanceof SystemMessage) {
                continue; // already lifted to top-level "system"
            }
            if (m instanceof UserMessage u) {
                out.add(userMessage(u));
            } else if (m instanceof AiMessage a) {
                out.add(aiMessage(a));
            } else if (m instanceof ToolExecutionResultMessage t) {
                out.add(toolResultMessage(t));
            }
            // Other future message types: skipped silently — better
            // than crashing the call when langchain4j adds a kind we
            // don't know yet.
        }
        return out;
    }

    private static Map<String, Object> userMessage(UserMessage u) {
        String text;
        try {
            text = u.singleText();
        } catch (RuntimeException e) {
            // Multimodal — for now we render a placeholder. Image /
            // file blocks land here once the engines start using them.
            text = "(unsupported multimodal content)";
        }
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "user");
        msg.put("content", text == null ? "" : text);
        return msg;
    }

    private static Map<String, Object> aiMessage(AiMessage a) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        if (a.text() != null && !a.text().isEmpty()) {
            Map<String, Object> textBlock = new LinkedHashMap<>();
            textBlock.put("type", "text");
            textBlock.put("text", a.text());
            blocks.add(textBlock);
        }
        if (a.hasToolExecutionRequests()) {
            for (ToolExecutionRequest req : a.toolExecutionRequests()) {
                Map<String, Object> block = new LinkedHashMap<>();
                block.put("type", "tool_use");
                block.put("id", req.id());
                block.put("name", req.name());
                block.put("input", parseJsonObject(req.arguments()));
                blocks.add(block);
            }
        }
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "assistant");
        msg.put("content", blocks);
        return msg;
    }

    private static Map<String, Object> toolResultMessage(
            ToolExecutionResultMessage trm) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "tool_result");
        block.put("tool_use_id", trm.id());
        block.put("content", trm.text() == null ? "" : trm.text());
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "user");
        msg.put("content", List.of(block));
        return msg;
    }

    /**
     * Best-effort JSON-object parse for the {@code input} field of a
     * tool_use block. langchain4j stores arguments as a JSON string;
     * Anthropic expects the parsed object.
     */
    private static Object parseJsonObject(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(json, Object.class);
        } catch (RuntimeException e) {
            // Bail to a single-field wrapper rather than crash the call —
            // worst case the model gets a malformed input echoed back.
            Map<String, Object> wrap = new LinkedHashMap<>();
            wrap.put("raw", json);
            return wrap;
        }
    }

    private static List<ChatMessage> safeMessages(ChatRequest request) {
        List<ChatMessage> messages = request.messages();
        return messages == null ? List.of() : messages;
    }

    private static Map<String, Object> cacheControl(@Nullable String ttl) {
        Map<String, Object> cc = new LinkedHashMap<>();
        cc.put("type", "ephemeral");
        if (ttl != null) {
            cc.put("ttl", ttl);
        }
        return cc;
    }

    private static @Nullable String ttlString(@Nullable CacheTtl ttl) {
        if (ttl == null) {
            return null;
        }
        return switch (ttl) {
            case DEFAULT_5MIN -> null; // omit → SDK uses 5min default
            case LONG_1H -> "1h";
        };
    }

    private static CacheBoundary effectiveBoundary(AiChatOptions options) {
        CacheBoundary b = options.getCacheBoundary();
        return b == null ? CacheBoundary.NONE : b;
    }

    // ──────────────────── JsonSchema → Anthropic input_schema ──

    /**
     * Translate a langchain4j {@link JsonSchemaElement} tree to the
     * JSON-Schema Map shape the Anthropic API expects on
     * {@code tools[].input_schema}.
     */
    private static Map<String, Object> jsonSchema(JsonSchemaElement element) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (element instanceof JsonObjectSchema obj) {
            out.put("type", "object");
            Map<String, Object> props = new LinkedHashMap<>();
            if (obj.properties() != null) {
                for (Map.Entry<String, JsonSchemaElement> e : obj.properties().entrySet()) {
                    props.put(e.getKey(), jsonSchema(e.getValue()));
                }
            }
            out.put("properties", props);
            if (obj.required() != null && !obj.required().isEmpty()) {
                out.put("required", new ArrayList<>(obj.required()));
            }
            if (obj.description() != null) {
                out.put("description", obj.description());
            }
        } else if (element instanceof JsonArraySchema arr) {
            out.put("type", "array");
            if (arr.items() != null) {
                out.put("items", jsonSchema(arr.items()));
            }
            if (arr.description() != null) {
                out.put("description", arr.description());
            }
        } else if (element instanceof JsonStringSchema s) {
            out.put("type", "string");
            if (s.description() != null) {
                out.put("description", s.description());
            }
        } else if (element instanceof JsonIntegerSchema i) {
            out.put("type", "integer");
            if (i.description() != null) {
                out.put("description", i.description());
            }
        } else if (element instanceof JsonNumberSchema n) {
            out.put("type", "number");
            if (n.description() != null) {
                out.put("description", n.description());
            }
        } else if (element instanceof JsonBooleanSchema b) {
            out.put("type", "boolean");
            if (b.description() != null) {
                out.put("description", b.description());
            }
        } else if (element instanceof JsonEnumSchema e) {
            out.put("type", "string");
            if (e.enumValues() != null) {
                out.put("enum", new ArrayList<>(e.enumValues()));
            }
            if (e.description() != null) {
                out.put("description", e.description());
            }
        } else {
            // Reference / anyOf / unknown — minimal "object" fallback so
            // the call doesn't crash at the SDK layer.
            out.put("type", "object");
        }
        return out;
    }
}
