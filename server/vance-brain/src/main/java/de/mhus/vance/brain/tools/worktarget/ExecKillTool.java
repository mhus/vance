package de.mhus.vance.brain.tools.worktarget;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component("workTargetExecKillTool")
public class ExecKillTool extends AbstractWorkTargetTool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "id", Map.of(
                            "type", "string",
                            "description", "Job id returned by exec_run.")),
            "required", List.of("id"));

    public ExecKillTool(WorkTargetDispatcher dispatcher) { super(dispatcher); }

    @Override public String name() { return "exec_kill"; }
    @Override public String description() {
        return "Kill an exec job at the active work target. Dispatches "
                + "to client_exec_kill or work_exec_kill.";
    }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public java.util.Set<String> labels() { return java.util.Set.of("write", "side-effect"); }

    @Override
    public java.util.Set<String> prakLabels() {
        return java.util.Set.of("execution", "shell");
    }

    @Override protected String clientBackend() { return "client_exec_kill"; }
    @Override protected String workBackend()   { return "work_exec_kill"; }
}
