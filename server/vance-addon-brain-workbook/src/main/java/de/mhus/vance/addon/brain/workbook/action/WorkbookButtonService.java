package de.mhus.vance.addon.brain.workbook.action;

import de.mhus.vance.toolpack.ToolException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Dispatcher for {@code vance-button} actions: injects every
 * {@link ButtonActionHandler} and routes by the fence's {@code type:}. The
 * controller knows no action type — it hands over the parsed fence YAML.
 * Duplicate {@link ButtonActionHandler#type()} values fail the boot;
 * an unregistered type fails the run (fail-closed — a button is explicit
 * user intent, and the fence round-trips loss-free either way).
 */
@Service
public class WorkbookButtonService {

    private final Map<String, ButtonActionHandler> handlers;

    public WorkbookButtonService(List<ButtonActionHandler> handlers) {
        Map<String, ButtonActionHandler> map = new LinkedHashMap<>();
        for (ButtonActionHandler handler : handlers) {
            ButtonActionHandler prev = map.put(handler.type(), handler);
            if (prev != null) {
                throw new IllegalStateException("duplicate button action type '" + handler.type()
                        + "' (" + prev.getClass().getSimpleName() + " vs "
                        + handler.getClass().getSimpleName() + ")");
            }
        }
        this.handlers = map;
    }

    /** All registered action types (for the {@code vance-button} validator). */
    public Set<String> types() {
        return handlers.keySet();
    }

    /** Dispatch by the fence's {@code type:} (default {@code script}, as the parser does). */
    public ButtonActionResult run(ButtonActionContext ctx) {
        Object rawType = ctx.buttonConfig().get("type");
        String type = rawType == null || rawType.toString().isBlank() ? "script" : rawType.toString();
        ButtonActionHandler handler = handlers.get(type);
        if (handler == null) {
            throw new ToolException("unknown button type '" + type + "' — registered types: " + handlers.keySet());
        }
        return handler.run(ctx);
    }
}
