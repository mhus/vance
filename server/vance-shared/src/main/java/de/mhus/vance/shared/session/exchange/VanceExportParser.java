package de.mhus.vance.shared.session.exchange;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.thinkprocess.PromptMode;
import de.mhus.vance.shared.memory.MemoryKind;
import de.mhus.vance.shared.session.exchange.SessionImportModel.ChatProcessSpec;
import de.mhus.vance.shared.session.exchange.SessionImportModel.ImportedMemory;
import de.mhus.vance.shared.session.exchange.SessionImportModel.ImportedTurn;
import de.mhus.vance.shared.session.exchange.SessionImportModel.ParsedImport;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Parses the Vance session export (the {@link SessionExportEmitter}
 * NDJSON) back into the format-neutral {@link ParsedImport}. Near-lossless:
 * the export is a serialisation of the Vance model, so the chat process is
 * reconstructed verbatim and the session stays continuable without a Brain
 * recipe resolution.
 *
 * <p>v1 imports the chat process (the one referenced by
 * {@code session_meta.chatProcessId}, else the first process), all
 * {@code message} rows folded onto it, and all {@code memory} rows.
 * {@code llm_trace}/{@code marvin_node}/{@code prak_run} rows are ignored.
 */
final class VanceExportParser {

    private VanceExportParser() {}

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    static ParsedImport parse(ObjectMapper mapper, List<JsonNode> rows) {
        String title = null;
        String displayName = null;
        String profile = null;
        String chatProcessId = null;
        Map<String, JsonNode> processesById = new LinkedHashMap<>();
        List<ImportedTurn> turns = new ArrayList<>();
        List<ImportedMemory> memories = new ArrayList<>();

        for (JsonNode row : rows) {
            String type = text(row, "type");
            if (type == null) continue;
            switch (type) {
                case "session_meta" -> {
                    title = text(row, "title");
                    displayName = text(row, "displayName");
                    profile = text(row, "profile");
                    chatProcessId = text(row, "chatProcessId");
                }
                case "process" -> {
                    String pid = firstNonBlank(text(row, "processId"), text(row, "id"));
                    if (pid != null) processesById.put(pid, row);
                }
                case "message" -> turns.add(messageTurn(mapper, row));
                case "memory" -> memories.add(memory(mapper, row));
                default -> { /* llm_trace / marvin_node / prak_run — ignored in v1 */ }
            }
        }

        JsonNode chatProcess = chatProcessId != null ? processesById.get(chatProcessId) : null;
        if (chatProcess == null && !processesById.isEmpty()) {
            chatProcess = processesById.values().iterator().next();
        }
        ChatProcessSpec spec = chatProcess == null ? null : processSpec(mapper, chatProcess);

        turns.sort(Comparator.comparing(
                ImportedTurn::at, Comparator.nullsLast(Comparator.naturalOrder())));

        return new ParsedImport(title, displayName, profile, spec, turns, memories);
    }

    private static ImportedTurn messageTurn(ObjectMapper mapper, JsonNode row) {
        ChatRole role = parseRole(text(row, "role"));
        String content = firstNonNull(text(row, "content"), "");
        String thinking = text(row, "thinking");
        Instant at = instant(row, "at");
        Set<String> tags = stringSet(row.get("tags"));
        Map<String, Object> meta = objectMap(mapper, row.get("meta"));
        String sourceId = text(row, "id");
        String archivedIn = text(row, "archivedInMemoryId");
        return new ImportedTurn(sourceId, role, content, thinking, at, tags, meta, archivedIn);
    }

    private static ImportedMemory memory(ObjectMapper mapper, JsonNode row) {
        MemoryKind kind = parseKind(text(row, "kind"));
        String title = text(row, "title");
        String content = firstNonNull(text(row, "content"), "");
        List<String> refs = new ArrayList<>(stringSet(row.get("sourceRefs")));
        String sourceId = text(row, "id");
        String supersededBy = text(row, "supersededByMemoryId");
        Instant at = instant(row, "at");
        Map<String, Object> metadata = objectMap(mapper, row.get("metadata"));
        return new ImportedMemory(sourceId, kind, title, content, refs, supersededBy, at, metadata);
    }

    private static ChatProcessSpec processSpec(ObjectMapper mapper, JsonNode p) {
        String engine = firstNonBlank(text(p, "thinkEngine"), "arthur");
        String version = text(p, "thinkEngineVersion");
        String recipeName = text(p, "recipeName");
        Map<String, Object> params = objectMap(mapper, p.get("engineParams"));
        String promptOverride = text(p, "promptOverride");
        String promptOverrideAppend = text(p, "promptOverrideAppend");
        PromptMode promptMode = parsePromptMode(text(p, "promptMode"));
        Set<String> allowedTools = p.has("allowedToolsOverride")
                ? stringSet(p.get("allowedToolsOverride")) : null;
        List<String> skills = new ArrayList<>();
        JsonNode skillsNode = p.get("activeSkills");
        if (skillsNode != null && skillsNode.isArray()) {
            for (JsonNode s : skillsNode) {
                String name = text(s, "name");
                if (name != null) skills.add(name);
            }
        }
        return new ChatProcessSpec(engine, version, recipeName, params,
                promptOverride, promptOverrideAppend, promptMode, allowedTools, skills);
    }

    // ─── Node helpers ─────────────────────────────────────────────────

    private static @Nullable String text(@Nullable JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        return v.asString();
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

    private static Set<String> stringSet(@Nullable JsonNode arr) {
        Set<String> out = new LinkedHashSet<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode n : arr) {
                if (!n.isNull()) out.add(n.asString());
            }
        }
        return out;
    }

    private static Map<String, Object> objectMap(ObjectMapper mapper, @Nullable JsonNode obj) {
        if (obj == null || obj.isNull() || !obj.isObject()) return new LinkedHashMap<>();
        return mapper.convertValue(obj, MAP_TYPE);
    }

    private static ChatRole parseRole(@Nullable String s) {
        if (s == null) return ChatRole.USER;
        try {
            return ChatRole.valueOf(s);
        } catch (IllegalArgumentException e) {
            return ChatRole.USER;
        }
    }

    private static MemoryKind parseKind(@Nullable String s) {
        if (s == null) return MemoryKind.OTHER;
        try {
            return MemoryKind.valueOf(s);
        } catch (IllegalArgumentException e) {
            return MemoryKind.OTHER;
        }
    }

    private static PromptMode parsePromptMode(@Nullable String s) {
        if (s == null) return PromptMode.APPEND;
        try {
            return PromptMode.valueOf(s);
        } catch (IllegalArgumentException e) {
            return PromptMode.APPEND;
        }
    }

    private static @Nullable String firstNonBlank(@Nullable String a, @Nullable String b) {
        if (a != null && !a.isBlank()) return a;
        return (b != null && !b.isBlank()) ? b : null;
    }

    private static String firstNonNull(@Nullable String a, String fallback) {
        return a != null ? a : fallback;
    }
}
