package de.mhus.vance.brain.tools;

import java.util.List;
import java.util.Optional;

/**
 * A place tools come from: built-in server beans, a connected client,
 * a plugin bundle. Sources are queried per scope so tenant-specific or
 * connection-specific tool sets can exist side by side.
 *
 * <p>v1 implementations return the same tools regardless of scope —
 * tenant filtering is a placeholder. The seam exists so we can add real
 * filtering later without changing callers.
 */
public interface ToolSource {

    /** Stable identifier written into {@code ToolSpec.source}. */
    String sourceId();

    /** Tools visible in the given scope. */
    List<Tool> tools(ToolInvocationContext ctx);

    /** Look up a single tool by name, or empty if unknown in this source. */
    default Optional<Tool> find(String name, ToolInvocationContext ctx) {
        return tools(ctx).stream().filter(t -> t.name().equals(name)).findFirst();
    }
}
