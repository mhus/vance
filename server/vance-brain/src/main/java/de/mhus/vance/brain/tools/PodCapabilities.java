package de.mhus.vance.brain.tools;

import de.mhus.vance.shared.workspace.WorkspaceProperties;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolCapabilities;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The host capabilities this pod offers to tools ({@link Tool#requiresCapabilities()}).
 * Derived from operator config: {@link ToolCapabilities#WORKSPACE} exists unless
 * {@code vance.workspace.mode=off}.
 */
@Component
public class PodCapabilities {

    private final Set<String> available;

    public PodCapabilities(WorkspaceProperties workspace) {
        this.available = workspace.getMode().accessible() ? Set.of(ToolCapabilities.WORKSPACE) : Set.of();
    }

    public Set<String> available() {
        return available;
    }

    /** {@code true} when every capability {@code tool} requires is available here. */
    public boolean supports(Tool tool) {
        return available.containsAll(tool.requiresCapabilities());
    }
}
