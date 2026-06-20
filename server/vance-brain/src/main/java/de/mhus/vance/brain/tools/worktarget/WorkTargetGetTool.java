package de.mhus.vance.brain.tools.worktarget;

import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.worktarget.WorkTarget;
import de.mhus.vance.shared.workspace.RootDirHandle;
import de.mhus.vance.shared.workspace.WorkspaceService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Reports the current {@link WorkTarget} for the calling process,
 * plus the set of alternatives it could switch to via
 * {@link WorkTargetSetTool}. Read-only.
 */
@Component
@RequiredArgsConstructor
public class WorkTargetGetTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(),
            "required", List.of());

    private final WorkTargetService workTargetService;
    private final ThinkProcessService thinkProcessService;
    private final WorkspaceService workspaceService;

    @Override
    public String name() {
        return "work_target_get";
    }

    @Override
    public String description() {
        return "Report the current WorkTarget — i.e. where the generic "
                + "file_* and exec_* tools currently dispatch to. Two "
                + "kinds: CLIENT (user's local host via Foot CLI) and "
                + "WORK (Brain-server workspace RootDir; dirName picks "
                + "which RootDir, null = process temp). The response "
                + "also lists alternatives the caller can switch to "
                + "via work_target_set: whether a foot client is "
                + "connected, and the existing workspace RootDir names "
                + "in the current project.";
    }

    @Override
    public boolean primary() {
        // Not primary — the work target is set per-spawn from the
        // recipe and rarely needs LLM-side inspection. Available via
        // find_tools / describe_tool when the LLM genuinely needs it.
        return false;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public java.util.Set<String> labels() {
        return java.util.Set.of("read-only");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        ThinkProcessDocument process = thinkProcessService.findById(ctx.processId())
                .orElseThrow(() -> new ToolException(
                        "work_target_get: process '" + ctx.processId() + "' not found"));
        WorkTarget current = workTargetService.current(process);

        Map<String, Object> available = new LinkedHashMap<>();
        available.put("clientConnected", workTargetService.clientConnected(process.getSessionId()));
        List<String> rootDirNames = new ArrayList<>();
        try {
            for (RootDirHandle h : workspaceService.listRootDirs(process.getTenantId(), process.getProjectId())) {
                rootDirNames.add(h.getDirName());
            }
        } catch (RuntimeException ex) {
            // Listing failure isn't fatal — report what we know.
            available.put("rootDirsError", ex.getMessage());
        }
        available.put("rootDirs", rootDirNames);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("current", current.toMap());
        out.put("available", available);
        return out;
    }
}
