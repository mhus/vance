package de.mhus.vance.brain.wowbagger;

import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The preflight: everything the agent wants verified BEFORE starting —
 * one call, no provider costs. Validates the source against the input
 * contract (JSONL: every record exactly one JSON object), counts records
 * (chunk estimate included), checks structure completeness and the
 * worker-model approval. Catches a broken conversion script and an
 * unapproved model at zero cost — the pool would otherwise surface the
 * same problems chunk by chunk.
 */
@Component
@RequiredArgsConstructor
public class WowbaggerCheckTool extends WowbaggerBaseTool {

    private final ThinkProcessService thinkProcessService;
    private final WowbaggerPoolService pool;

    private static final Map<String, Object> SCHEMA = Map.of("type", "object", "properties", Map.of());

    @Override
    public String name() {
        return "wowbagger_check";
    }

    @Override
    public String description() {
        return "Preflight the configured run without worker calls: is the source "
                + "readable in the run root, does EVERY record satisfy the input contract "
                + "(jsonl = exactly one JSON object per line), how many records/chunks "
                + "will it be, is the structure complete and the worker model approved? "
                + "Run this after wowbagger_configure and before wowbagger_start — "
                + "conversion bugs and unapproved models surface here at zero cost.";
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public java.util.Set<String> labels() {
        return java.util.Set.of("read");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) throws ToolException {
        ThinkProcessDocument process = process(thinkProcessService, ctx);
        WowbaggerState structure = pool.structure(process.getId());
        return pool.preflight(process, structure);
    }
}
