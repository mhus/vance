package de.mhus.vance.brain.vogon;

import de.mhus.vance.api.magrathea.MagratheaProcessDto;
import de.mhus.vance.brain.magrathea.MagratheaGateChatAnswerService;
import de.mhus.vance.shared.magrathea.MagratheaStateProjector;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Read-only on-demand view of THIS process' run: status, plan name, current
 * state, elapsed time, run variables, result — more than the prompt status
 * block's compact excerpt.
 *
 * <p>Why a tool at all: the status block is re-rendered per turn, which
 * answers "how is it going?" mid-run without a call — but it is a passive
 * excerpt. When the user explicitly wants the details ("what exactly is in
 * the result?", "show me the run's variables"), this tool fetches the full
 * projection on demand (Hactar {@code hactar_status} rationale).
 */
@Component
@ConditionalOnProperty(value = "vance.services.magrathea", havingValue = "true", matchIfMissing = false)
public class VogonStatusTool extends VogonBaseTool {

    private final ThinkProcessService thinkProcessService;
    private final MagratheaStateProjector projector;
    private final MagratheaGateChatAnswerService gateChatAnswerService;

    public VogonStatusTool(
            ThinkProcessService thinkProcessService,
            MagratheaStateProjector projector,
            MagratheaGateChatAnswerService gateChatAnswerService) {
        this.thinkProcessService = thinkProcessService;
        this.projector = projector;
        this.gateChatAnswerService = gateChatAnswerService;
    }

    @Override
    public String name() {
        return "vogon_status";
    }

    @Override
    public String description() {
        return "Read the current plan run of this process on demand: run status (live/paused/"
                + "finished/failed/stopped), plan name, current state, elapsed time, run variables "
                + "and — on a terminal run — the result payload, plus the open gate when the run "
                + "is waiting at one. The status block in your prompt already carries the compact "
                + "picture; call this when the user wants the details.";
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return Map.of("type", "object", "properties", Map.of(), "required", java.util.List.of());
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        ThinkProcessDocument process = process(thinkProcessService, ctx);
        String runId = runId(process);
        Map<String, Object> out = new LinkedHashMap<>();
        if (runId == null) {
            out.put("run", false);
            out.put("note", "no plan run yet — start one with vogon_start");
            return out;
        }
        out.put("run", true);
        out.put("workflowRunId", runId);
        Optional<MagratheaProcessDto> run = projector.project(process.getTenantId(), process.getProjectId(), runId);
        if (run.isEmpty()) {
            out.put("note", "run left no journal (pruned or foreign)");
            return out;
        }
        MagratheaProcessDto dto = run.get();
        out.put("status", dto.getStatus() == null ? null : dto.getStatus().name());
        out.put("workflowName", dto.getWorkflowName());
        out.put("currentState", dto.getCurrentState());
        out.put(
                "elapsedMs",
                dto.getCreatedAt() == null
                        ? null
                        : System.currentTimeMillis() - dto.getCreatedAt().toEpochMilli());
        if (dto.getVars() != null && !dto.getVars().isEmpty()) {
            out.put("vars", dto.getVars());
        }
        if (dto.getResult() != null && !dto.getResult().isEmpty()) {
            out.put("result", dto.getResult());
        }
        gateChatAnswerService.findOpenGateItem(process.getTenantId(), runId).ifPresent(item -> {
            Map<String, Object> gate = new LinkedHashMap<>();
            gate.put("type", String.valueOf(item.getType()));
            gate.put("title", item.getTitle());
            out.put("openGate", gate);
        });
        return out;
    }
}
