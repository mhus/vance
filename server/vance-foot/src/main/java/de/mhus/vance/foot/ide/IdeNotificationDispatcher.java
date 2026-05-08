package de.mhus.vance.foot.ide;

import de.mhus.vance.foot.ide.dto.AtMentioned;
import de.mhus.vance.foot.ide.dto.Position;
import de.mhus.vance.foot.ide.dto.Range;
import de.mhus.vance.foot.ide.dto.SelectionChanged;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Translates raw {@link IdeMcpClient.Notification}s into typed events and
 * fans them out to registered {@link IdeBridgeListener}s. Listeners are
 * called on the WebSocket reader thread — handlers must not block.
 *
 * <p>Unknown notification methods are dropped silently; the bridge does
 * not need to react to {@code notifications/cancelled} and similar housekeeping
 * frames in v1.
 */
@Component
@Slf4j
public class IdeNotificationDispatcher {

    private final List<IdeBridgeListener> listeners = new CopyOnWriteArrayList<>();

    public void register(IdeBridgeListener listener) {
        listeners.add(listener);
    }

    public void unregister(IdeBridgeListener listener) {
        listeners.remove(listener);
    }

    public void dispatch(IdeMcpClient.Notification notification) {
        switch (notification.method()) {
            case "at_mentioned" -> {
                AtMentioned mention = parseAtMentioned(notification.params());
                if (mention != null) {
                    for (IdeBridgeListener l : listeners) {
                        safe(() -> l.onAtMentioned(mention));
                    }
                }
            }
            case "selection_changed" -> {
                SelectionChanged sel = parseSelectionChanged(notification.params());
                if (sel != null) {
                    for (IdeBridgeListener l : listeners) {
                        safe(() -> l.onSelectionChanged(sel));
                    }
                }
            }
            default -> log.debug("ignoring notification {}", notification.method());
        }
    }

    public void notifyConnectionState(boolean connected) {
        for (IdeBridgeListener l : listeners) {
            safe(() -> l.onConnectionStateChanged(connected));
        }
    }

    static @Nullable AtMentioned parseAtMentioned(@Nullable JsonNode params) {
        if (params == null || !params.isObject()) {
            return null;
        }
        String filePath = textOrNull(params, "filePath");
        if (filePath == null) {
            return null;
        }
        Integer lineStart = intOrNull(params, "lineStart");
        Integer lineEnd = intOrNull(params, "lineEnd");
        return new AtMentioned(filePath, lineStart, lineEnd);
    }

    static @Nullable SelectionChanged parseSelectionChanged(@Nullable JsonNode params) {
        if (params == null || !params.isObject()) {
            return null;
        }
        String filePath = textOrNull(params, "filePath");
        String text = textOrNull(params, "text");
        Range selection = parseRange(params.get("selection"));
        return new SelectionChanged(filePath, selection, text);
    }

    private static @Nullable Range parseRange(@Nullable JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        Position start = parsePosition(node.get("start"));
        Position end = parsePosition(node.get("end"));
        if (start == null || end == null) {
            return null;
        }
        return new Range(start, end);
    }

    private static @Nullable Position parsePosition(@Nullable JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        Integer line = intOrNull(node, "line");
        Integer character = intOrNull(node, "character");
        if (line == null || character == null) {
            return null;
        }
        return new Position(line, character);
    }

    private static @Nullable String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    private static @Nullable Integer intOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asInt(0);
    }

    private static void safe(Runnable runnable) {
        try {
            runnable.run();
        } catch (RuntimeException e) {
            log.warn("listener threw: {}", e.toString());
        }
    }
}
