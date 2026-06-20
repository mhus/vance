package de.mhus.vance.brain.tools.worktarget;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component("workTargetExecTailTool")
public class ExecTailTool extends AbstractWorkTargetTool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "id", Map.of(
                            "type", "string",
                            "description", "Job id returned by exec_run."),
                    "lines", Map.of(
                            "type", "integer",
                            "description", "Number of trailing lines to return (per stream).")),
            "required", List.of("id"));

    public ExecTailTool(WorkTargetDispatcher dispatcher) { super(dispatcher); }

    @Override public String name() { return "exec_tail"; }
    @Override public String description() {
        return "Tail stdout/stderr of an exec job at the active work "
                + "target. Dispatches to client_exec_tail or work_exec_tail.";
    }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public java.util.Set<String> labels() { return java.util.Set.of("read-only"); }
    @Override protected String clientBackend() { return "client_exec_tail"; }
    @Override protected String workBackend()   { return "work_exec_tail"; }
}
