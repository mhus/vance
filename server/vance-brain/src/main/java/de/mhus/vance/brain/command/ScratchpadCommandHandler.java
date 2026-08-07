package de.mhus.vance.brain.command;

import de.mhus.vance.brain.prompt.ScratchpadPromptBlock;
import de.mhus.vance.shared.memory.MemoryDocument;
import de.mhus.vance.shared.memory.ScratchpadService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Generic {@code scratchpad} verb — lets a human read and clear the notes
 * an engine took on the running process (engine-agnostic). Subcommands
 * (in the command's {@code text} argument):
 *
 * <ul>
 *   <li>{@code list} (or empty) — every slot with its size;</li>
 *   <li>{@code get <key>} — one slot's full content, uncapped;</li>
 *   <li>{@code delete <key>} — drop a slot (tombstone, audit-friendly);</li>
 *   <li>{@code block} — the rendered prompt block verbatim, i.e. exactly
 *       what the model sees including caps and size hints.</li>
 * </ul>
 *
 * <p><b>Read and clear, never write.</b> {@code set} is deliberately
 * absent: the scratchpad is the engine's private notepad, and the channel
 * for telling it something is the chat. {@code delete} is the exception
 * because the prompt block ({@link ScratchpadPromptBlock}) puts every slot
 * into <em>every</em> turn — a stale note is context the user can see but
 * otherwise only get rid of by ending the process.
 *
 * <p>Scope is the addressed process, matching the tool family: notes of a
 * closed process or of a worker process are not reachable here. See
 * {@code planning/scratchpad-review.md} §7.
 */
@Component
@RequiredArgsConstructor
public class ScratchpadCommandHandler implements EngineCommandHandler {

    private final ScratchpadService scratchpadService;

    @Override
    public String verb() {
        return "scratchpad";
    }

    @Override
    public EngineCommandResult handle(ThinkProcessDocument process, EngineCommand command) {
        String processId = process.getId();
        if (processId == null || processId.isBlank()) {
            return EngineCommandResult.error("process is not persisted — no scratchpad");
        }
        String[] head = splitFirstToken(argText(command));
        String sub = head[0].isEmpty() ? "list" : head[0].toLowerCase(Locale.ROOT);
        String rest = head[1];
        return switch (sub) {
            case "list" -> list(process, processId);
            case "get" -> get(process, processId, rest);
            case "delete", "del", "rm" -> delete(process, processId, rest);
            case "block" -> block(process, processId);
            default -> EngineCommandResult.error(
                    "unknown subcommand '" + sub
                            + "' (list | get <key> | delete <key> | block)");
        };
    }

    private EngineCommandResult list(ThinkProcessDocument process, String processId) {
        List<MemoryDocument> slots = scratchpadService.list(process.getTenantId(), processId);
        if (slots.isEmpty()) {
            return EngineCommandResult.ok("no slots", null);
        }
        StringBuilder detail = new StringBuilder();
        for (MemoryDocument slot : slots) {
            detail.append('\n').append(slot.getTitle())
                    .append(" — ").append(slot.getContent().length()).append(" chars");
        }
        return EngineCommandResult.ok(slots.size() + " slot(s)", detail.toString());
    }

    private EngineCommandResult get(
            ThinkProcessDocument process, String processId, String rest) {
        String title = rest.trim();
        if (title.isEmpty()) {
            return EngineCommandResult.error("get requires a key: //scratchpad get <key>");
        }
        return scratchpadService.get(process.getTenantId(), processId, title)
                .map(slot -> EngineCommandResult.ok(
                        title + " — " + slot.getContent().length() + " chars",
                        "\n" + slot.getContent()))
                .orElseGet(() -> EngineCommandResult.ok(title + " (not set)", null));
    }

    private EngineCommandResult delete(
            ThinkProcessDocument process, String processId, String rest) {
        String title = rest.trim();
        if (title.isEmpty()) {
            return EngineCommandResult.error("delete requires a key: //scratchpad delete <key>");
        }
        boolean deleted = scratchpadService.delete(process.getTenantId(), processId, title);
        return EngineCommandResult.ok(
                title + (deleted ? " deleted" : " (not set)"), null);
    }

    private EngineCommandResult block(ThinkProcessDocument process, String processId) {
        List<MemoryDocument> slots = scratchpadService.list(process.getTenantId(), processId);
        String body = ScratchpadPromptBlock.render(slots);
        if (body.isEmpty()) {
            return EngineCommandResult.ok("no block injected — inventory empty", null);
        }
        return EngineCommandResult.ok(
                body.length() + " chars injected per turn", "\n" + body);
    }

    private static String argText(EngineCommand command) {
        Object text = command.args().get("text");
        return text == null ? "" : text.toString().trim();
    }

    private static String[] splitFirstToken(String s) {
        String t = s.trim();
        if (t.isEmpty()) {
            return new String[] {"", ""};
        }
        for (int i = 0; i < t.length(); i++) {
            if (Character.isWhitespace(t.charAt(i))) {
                return new String[] {t.substring(0, i), t.substring(i + 1).trim()};
            }
        }
        return new String[] {t, ""};
    }
}
