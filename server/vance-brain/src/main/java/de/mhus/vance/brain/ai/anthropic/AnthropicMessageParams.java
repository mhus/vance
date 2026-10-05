package de.mhus.vance.brain.ai.anthropic;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.DocumentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlockParam;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Typed half of the Anthropic request mapping: wire maps → SDK {@link MessageParam}s.
 *
 * <p>{@link AnthropicRequestMapper} builds the message array as plain maps — that is the
 * contract its unit tests assert on, and the exact JSON shape Vance wants on the wire.
 * The SDK however treats {@code messages} as a <b>required typed field</b> of
 * {@code MessageCreateParams}: raw {@code putAdditionalBodyProperty} values serialize
 * fine but do not satisfy the required check, so a request built from maps alone dies
 * client-side with {@code `messages` is required, but was not set}. This class is the
 * mechanical translation at that boundary, one typed builder per block shape the
 * mapper emits — no JSON tricks, no reflection.
 *
 * <p>Why not map to the typed API directly in the mapper: the map form is what the
 * layout tests pin (block order, marker positions, string-vs-block rendering), and it
 * doubles as the diagnostic dump of a request. Keeping one source of truth for the
 * wire shape and translating at the SDK boundary keeps both concerns honest.
 *
 * <p>The block vocabulary is closed on purpose: {@code text}, {@code image},
 * {@code document}, {@code tool_use}, {@code tool_result} — exactly what the mapper
 * produces. Anything else is a bug in that pairing and fails loudly instead of
 * silently dropping content from a model turn.
 */
@NullMarked
final class AnthropicMessageParams {

    private AnthropicMessageParams() {}

    /** Translate the mapper's message array into typed SDK params. */
    static List<MessageParam> messageParams(List<Map<String, Object>> messages) {
        List<MessageParam> out = new ArrayList<>(messages.size());
        for (Map<String, Object> message : messages) {
            out.add(messageParam(message));
        }
        return List.copyOf(out);
    }

    private static MessageParam messageParam(Map<String, Object> raw) {
        Object role = raw.get("role");
        Object content = raw.get("content");
        if (!(role instanceof String roleName) || content == null) {
            throw new IllegalStateException("Anthropic message needs 'role' and 'content': " + raw.keySet());
        }
        MessageParam.Builder builder = MessageParam.builder().role(MessageParam.Role.of(roleName));
        if (content instanceof String text) {
            return builder.content(text).build();
        }
        if (content instanceof List<?> blocks) {
            List<ContentBlockParam> params = new ArrayList<>(blocks.size());
            for (Object block : blocks) {
                params.add(contentBlock(asMap(block, "content block")));
            }
            return builder.contentOfBlockParams(params).build();
        }
        throw new IllegalStateException("Unsupported Anthropic message content shape: "
                + content.getClass().getName());
    }

    private static ContentBlockParam contentBlock(Map<String, Object> block) {
        Object type = block.get("type");
        if ("text".equals(type)) {
            TextBlockParam.Builder builder = TextBlockParam.builder().text(asString(block.get("text"), "text"));
            cacheControl(block).ifPresent(builder::cacheControl);
            return ContentBlockParam.ofText(builder.build());
        }
        if ("image".equals(type)) {
            ImageBlockParam.Builder builder =
                    ImageBlockParam.builder().source(imageSource(asMap(block.get("source"), "image source")));
            cacheControl(block).ifPresent(builder::cacheControl);
            return ContentBlockParam.ofImage(builder.build());
        }
        if ("document".equals(type)) {
            DocumentBlockParam.Builder builder =
                    DocumentBlockParam.builder().source(documentSource(asMap(block.get("source"), "document source")));
            cacheControl(block).ifPresent(builder::cacheControl);
            return ContentBlockParam.ofDocument(builder.build());
        }
        if ("tool_use".equals(type)) {
            return ContentBlockParam.ofToolUse(ToolUseBlockParam.builder()
                    .id(asString(block.get("id"), "tool_use.id"))
                    .name(asString(block.get("name"), "tool_use.name"))
                    .input(toolInput(block.get("input")))
                    .build());
        }
        if ("tool_result".equals(type)) {
            ToolResultBlockParam.Builder builder = ToolResultBlockParam.builder()
                    .toolUseId(asString(block.get("tool_use_id"), "tool_result.tool_use_id"))
                    .content(asString(block.get("content"), "tool_result.content"));
            cacheControl(block).ifPresent(builder::cacheControl);
            return ContentBlockParam.ofToolResult(builder.build());
        }
        throw new IllegalStateException("Unsupported Anthropic content block type: " + type);
    }

    private static ImageBlockParam.Source imageSource(Map<String, Object> source) {
        String type = asString(source.get("type"), "image source.type");
        return switch (type) {
            case "url" -> ImageBlockParam.Source.ofUrl(asString(source.get("url"), "image source.url"));
            case "base64" ->
                ImageBlockParam.Source.ofBase64(Base64ImageSource.builder()
                        .mediaType(Base64ImageSource.MediaType.of(
                                asString(source.get("media_type"), "image source.media_type")))
                        .data(asString(source.get("data"), "image source.data"))
                        .build());
            default -> throw new IllegalStateException("Unsupported image source type: " + type);
        };
    }

    private static DocumentBlockParam.Source documentSource(Map<String, Object> source) {
        String type = asString(source.get("type"), "document source.type");
        return switch (type) {
            case "url" -> DocumentBlockParam.Source.ofUrl(asString(source.get("url"), "document source.url"));
            case "base64" -> DocumentBlockParam.Source.ofBase64(asString(source.get("data"), "document source.data"));
            default -> throw new IllegalStateException("Unsupported document source type: " + type);
        };
    }

    /**
     * Tool arguments. langchain4j hands them over as the parsed object the mapper
     * produced; the SDK wants them as its own open {@code input} bag.
     */
    private static ToolUseBlockParam.Input toolInput(@Nullable Object raw) {
        Map<String, Object> fields = raw == null ? Map.of() : asMap(raw, "tool_use.input");
        Map<String, JsonValue> properties = new LinkedHashMap<>();
        fields.forEach((key, value) -> properties.put(key, JsonValue.from(value)));
        return ToolUseBlockParam.Input.builder()
                .additionalProperties(properties)
                .build();
    }

    /** {@code {"type":"ephemeral"[,"ttl":"1h"]}} on a block — the marker itself. */
    private static Optional<CacheControlEphemeral> cacheControl(Map<String, Object> block) {
        if (!(block.get("cache_control") instanceof Map<?, ?> raw)) {
            return Optional.empty();
        }
        CacheControlEphemeral.Builder builder = CacheControlEphemeral.builder().type(JsonValue.from("ephemeral"));
        if ("1h".equals(raw.get("ttl"))) {
            builder.ttl(CacheControlEphemeral.Ttl.TTL_1H);
        }
        return Optional.of(builder.build());
    }

    private static Map<String, Object> asMap(@Nullable Object value, String what) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        throw new IllegalStateException("Anthropic " + what + " must be an object, got: " + value);
    }

    private static String asString(@Nullable Object value, String what) {
        if (value instanceof String s) {
            return s;
        }
        throw new IllegalStateException("Anthropic " + what + " must be a string, got: " + value);
    }
}
