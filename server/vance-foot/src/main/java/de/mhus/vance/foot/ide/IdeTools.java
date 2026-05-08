package de.mhus.vance.foot.ide;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Typed wrapper over {@link IdeMcpClient}'s {@code tools/call} surface.
 * Methods only call tools that the server advertised in {@code tools/list};
 * a missing tool throws {@link IllegalStateException} with the tool name
 * so callers get a clear error instead of a generic RPC failure.
 *
 * <p>v1 covers the read-only surface needed by the {@code /ide} slash
 * commands: opened files, diagnostics. Writing tools (open, diff, format)
 * are wired up when the engine integration in §3.6 lands.
 */
public final class IdeTools {

    private final IdeMcpClient client;
    private final ObjectMapper json = JsonMapper.builder().build();

    public IdeTools(IdeMcpClient client) {
        this.client = client;
    }

    /**
     * Returns the absolute paths of all files currently open in the IDE.
     * The plugin returns them as a single newline-separated string in the
     * tool result content; we split it back into a list.
     */
    public List<Path> getOpenedFiles() throws IdeRpcException, TimeoutException, InterruptedException {
        JsonNode result = call("get_all_opened_file_paths", json.createObjectNode());
        String text = extractTextContent(result);
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<Path> paths = new ArrayList<>();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                paths.add(Path.of(trimmed));
            }
        }
        return List.copyOf(paths);
    }

    /**
     * Fetches diagnostics from the IDE. {@code file == null} returns
     * project-wide diagnostics; otherwise scoped to the given file.
     * The raw payload is returned for now — pretty-printing happens in
     * the slash-command renderer, not here.
     */
    public JsonNode getDiagnostics(@Nullable Path file)
            throws IdeRpcException, TimeoutException, InterruptedException {
        ObjectNode args = json.createObjectNode();
        if (file != null) {
            args.put("uri", "file://" + file.toAbsolutePath());
        }
        return call("getDiagnostics", args);
    }

    private JsonNode call(String tool, JsonNode arguments)
            throws IdeRpcException, TimeoutException, InterruptedException {
        if (!client.tools().isEmpty() && !client.tools().contains(tool)) {
            throw new IllegalStateException("IDE plugin does not advertise tool: " + tool);
        }
        ObjectNode params = json.createObjectNode()
                .put("name", tool);
        params.set("arguments", arguments);
        return client.request("tools/call", params, Duration.ofSeconds(10));
    }

    /**
     * MCP {@code tools/call} responses come back as a {@code content} array
     * of typed blocks; for the read tools the plugin uses, only one
     * {@code text}-block is present. This helper extracts that text.
     */
    public static @Nullable String extractTextContent(@Nullable JsonNode toolResult) {
        if (toolResult == null) {
            return null;
        }
        JsonNode content = toolResult.get("content");
        if (content == null || !content.isArray()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (JsonNode block : content) {
            JsonNode type = block.get("type");
            if (type != null && "text".equals(type.asString())) {
                JsonNode text = block.get("text");
                if (text != null && text.isString()) {
                    if (out.length() > 0) {
                        out.append('\n');
                    }
                    out.append(text.asString());
                }
            }
        }
        return out.length() == 0 ? null : out.toString();
    }
}
