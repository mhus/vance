package de.mhus.vance.shared.session.exchange;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.shared.session.exchange.SessionImportModel.ImportedTurn;
import de.mhus.vance.shared.session.exchange.SessionImportModel.ParsedImport;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Parses a Claude-Code {@code *.jsonl} conversation export into the
 * format-neutral {@link ParsedImport}. Lossy by design: the readable
 * conversation (user/assistant text + thinking) maps cleanly; tool
 * mechanics are folded into the message text (Vance has no TOOL role) and
 * client scaffolding (command caveats, file-history, attachments,
 * task-notifications) is dropped.
 *
 * <p>Produces no chat process (synthesised by the importer from the chosen
 * engine/recipe) and no memories.
 */
final class ClaudeExportParser {

    private ClaudeExportParser() {}

    private static final int TOOL_RESULT_MAX_LINES = 12;
    private static final int TOOL_RESULT_LINE_CAP = 400;
    private static final Pattern COMMAND_NAME =
            Pattern.compile("<command-name>(.*?)</command-name>", Pattern.DOTALL);

    static ParsedImport parse(ObjectMapper mapper, List<JsonNode> rows) {
        String title = null;
        List<ImportedTurn> turns = new ArrayList<>();

        for (JsonNode row : rows) {
            String type = text(row, "type");
            if (type == null) continue;
            if ("ai-title".equals(type)) {
                String t = text(row, "aiTitle");
                if (t != null && !t.isBlank()) title = t.trim();  // keep the latest
                continue;
            }
            if (!"user".equals(type) && !"assistant".equals(type)) continue;

            ImportedTurn turn = turn(row, type);
            if (turn != null) turns.add(turn);
        }

        return new ParsedImport(title, null, null, null, turns, List.of());
    }

    private static @Nullable ImportedTurn turn(JsonNode row, String type) {
        JsonNode message = row.get("message");
        if (message == null || message.isNull()) return null;
        ChatRole role = "assistant".equals(type) ? ChatRole.ASSISTANT : ChatRole.USER;

        StringBuilder content = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        Set<String> tags = new LinkedHashSet<>();

        JsonNode c = message.get("content");
        if (c != null && c.isString()) {
            appendUserString(content, c.asString());
        } else if (c != null && c.isArray()) {
            for (JsonNode part : c) {
                appendPart(part, content, thinking, tags);
            }
        }

        String body = content.toString().strip();
        String think = thinking.toString().strip();
        if (body.isEmpty() && think.isEmpty()) return null;  // pure scaffolding

        Instant at = instant(row, "timestamp");
        Map<String, Object> meta = new LinkedHashMap<>();
        return new ImportedTurn(null, role, body,
                think.isEmpty() ? null : think, at, tags, meta, null);
    }

    /** String-content user turns are often slash-command scaffolding. */
    private static void appendUserString(StringBuilder content, String raw) {
        String s = raw.strip();
        if (s.startsWith("<local-command") ) {
            return;  // caveat / stdout noise
        }
        if (s.startsWith("<command-")) {
            Matcher m = COMMAND_NAME.matcher(s);
            if (m.find()) append(content, "$ " + m.group(1).strip());
            return;
        }
        append(content, s);
    }

    private static void appendPart(JsonNode part, StringBuilder content,
            StringBuilder thinking, Set<String> tags) {
        if (part == null || !part.isObject()) return;
        String ptype = text(part, "type");
        if (ptype == null) return;
        switch (ptype) {
            case "text" -> append(content, textOr(part, "text"));
            case "thinking" -> append(thinking, textOr(part, "thinking"));
            case "tool_use" -> {
                String name = firstNonBlank(text(part, "name"), "tool");
                append(content, "⚙ " + name + "(" + summariseInput(part.get("input")) + ")");
                tags.add("TOOL_CALL:" + name);
            }
            case "tool_result" -> {
                String r = foldToolResult(part.get("content"));
                if (!r.isBlank()) append(content, "↳ result:\n" + r);
            }
            default -> { /* image/other parts dropped */ }
        }
    }

    private static String summariseInput(@Nullable JsonNode input) {
        if (input == null || !input.isObject()) return "";
        String[] prefer = {"command", "file_path", "path", "pattern", "query",
                "description", "prompt", "url"};
        for (String key : prefer) {
            JsonNode v = input.get(key);
            if (v != null && v.isString()) return key + "=" + truncate(v.asString(), 80);
        }
        for (Map.Entry<String, JsonNode> e : input.properties()) {
            return e.getKey() + "=" + truncate(String.valueOf(e.getValue()), 80);
        }
        return "";
    }

    private static String foldToolResult(@Nullable JsonNode content) {
        if (content == null || content.isNull()) return "";
        String text;
        if (content.isString()) {
            text = content.asString();
        } else if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode c : content) {
                if (c.isString()) {
                    sb.append(c.asString());
                } else if (c.isObject() && "text".equals(text(c, "type"))) {
                    sb.append(textOr(c, "text"));
                }
                sb.append('\n');
            }
            text = sb.toString();
        } else {
            text = content.toString();
        }
        String[] lines = text.strip().split("\n", -1);
        StringBuilder out = new StringBuilder();
        int shown = Math.min(lines.length, TOOL_RESULT_MAX_LINES);
        for (int i = 0; i < shown; i++) {
            out.append("  ").append(truncate(lines[i], TOOL_RESULT_LINE_CAP)).append('\n');
        }
        if (lines.length > shown) {
            out.append("  … (+").append(lines.length - shown).append(" more lines)");
        }
        return out.toString().stripTrailing();
    }

    // ─── helpers ──────────────────────────────────────────────────────

    private static void append(StringBuilder sb, @Nullable String s) {
        if (s == null || s.isBlank()) return;
        if (sb.length() > 0) sb.append("\n\n");
        sb.append(s.strip());
    }

    private static @Nullable String text(@Nullable JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        return v.asString();
    }

    private static String textOr(JsonNode node, String field) {
        String s = text(node, field);
        return s == null ? "" : s;
    }

    private static @Nullable Instant instant(JsonNode node, String field) {
        String s = text(node, field);
        if (s == null || s.isBlank()) return null;
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static String truncate(String s, int limit) {
        String t = s.strip();
        return t.length() <= limit ? t : t.substring(0, limit).strip() + " …";
    }

    private static String firstNonBlank(@Nullable String a, String fallback) {
        return (a != null && !a.isBlank()) ? a : fallback;
    }
}
