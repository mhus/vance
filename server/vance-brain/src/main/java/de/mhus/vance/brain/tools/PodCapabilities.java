package de.mhus.vance.brain.tools;

import de.mhus.vance.shared.workspace.WorkspaceProperties;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolCapabilities;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The host capabilities this pod offers to tools ({@link Tool#requiresCapabilities()}).
 * {@link ToolCapabilities#WORKSPACE} comes from operator config (absent with
 * {@code vance.workspace.mode=off}); the others from {@link PodCapabilityContributor}s
 * that check what the image actually carries — {@link ToolCapabilities#AUDIO_TOOLS}
 * only where ffmpeg runs.
 */
@Component
@Slf4j
public class PodCapabilities {

    private final Set<String> available;

    public PodCapabilities(WorkspaceProperties workspace, List<PodCapabilityContributor> contributors) {
        Set<String> all = new HashSet<>();
        if (workspace.getMode().accessible()) {
            all.add(ToolCapabilities.WORKSPACE);
        }
        for (PodCapabilityContributor contributor : contributors) {
            all.addAll(contributor.capabilities());
        }
        this.available = Set.copyOf(all);
        log.info("Pod capabilities: {}", available.isEmpty() ? "none" : available);
    }

    public Set<String> available() {
        return available;
    }

    /** {@code true} when every capability {@code tool} requires is available here. */
    public boolean supports(Tool tool) {
        return available.containsAll(tool.requiresCapabilities());
    }
}
