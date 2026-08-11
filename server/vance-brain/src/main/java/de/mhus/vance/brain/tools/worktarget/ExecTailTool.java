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
                    "n", Map.of(
                            "type", "integer",
                            "description", "Number of trailing lines to return (default 10, max 500)."),
                    "stream", Map.of(
                            "type", "string",
                            "enum", List.of("stdout", "stderr"),
                            "description", "Which stream to tail; default stdout.")),
            "required", List.of("id"));

    public ExecTailTool(WorkTargetDispatcher dispatcher) { super(dispatcher); }

    @Override public String name() { return "exec_tail"; }
    @Override public String description() {
        return "Tail stdout/stderr of an exec job at the active work "
                + "target. Dispatches to client_exec_tail or work_exec_tail.";
    }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public java.util.Set<String> labels() { return java.util.Set.of("read-only"); }

    @Override
    public java.util.Set<String> prakLabels() {
        return java.util.Set.of("execution", "shell");
    }

    @Override protected String clientBackend() { return "client_exec_tail"; }
    @Override protected String workBackend()   { return "work_exec_tail"; }
}
