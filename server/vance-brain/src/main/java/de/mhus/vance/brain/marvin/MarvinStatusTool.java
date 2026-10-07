package de.mhus.vance.brain.marvin;

import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Read-only on-demand view of THIS process' tree: node statistics, root
 * goal, the current node with its phase, open inbox questions and the
 * root result on a terminal run — more than the prompt status block's
 * compact excerpt (Hactar {@code hactar_status} / Vogon {@code vogon_status}
 * rationale: the status block answers "how is it going?" without a call,
 * this tool fetches the details when the user wants them).
 */
@Component
public class MarvinStatusTool extends MarvinBaseTool {

    private final ThinkProcessService thinkProcessService;
    private final MarvinEngine engine;

    public MarvinStatusTool(ThinkProcessService thinkProcessService, MarvinEngine engine) {
        this.thinkProcessService = thinkProcessService;
        this.engine = engine;
    }

    @Override
    public String name() {
        return "marvin_status";
    }

    @Override
    public String description() {
        return "Read the current tree run of this process on demand: node statistics "
                + "(done/running/waiting/pending/failed), the root goal, the current node "
                + "with its phase, the open inbox questions (which the human answers via the "
                + "inbox form), and the root result on a terminal run. The status block in "
                + "your prompt already carries the compact picture; call this when the user "
                + "wants the details.";
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return Map.of("type", "object", "properties", Map.of(), "required", List.of());
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        ThinkProcessDocument process = process(thinkProcessService, ctx);
        return engine.readTreeStatus(process);
    }
}
