package de.mhus.vance.brain.tools.worktarget;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ExecStatusTool extends AbstractWorkTargetTool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "id", Map.of(
                            "type", "string",
                            "description", "Job id returned by exec_run.")),
            "required", List.of("id"));

    public ExecStatusTool(WorkTargetDispatcher dispatcher) { super(dispatcher); }

    @Override public String name() { return "exec_status"; }
    @Override public String description() {
        return "Check status and inline output of a previously started "
                + "exec job at the active work target. Dispatches to "
                + "client_exec_status or work_exec_status.";
    }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public java.util.Set<String> labels() { return java.util.Set.of("read-only"); }
    @Override protected String clientBackend() { return "client_exec_status"; }
    @Override protected String workBackend()   { return "work_exec_status"; }
}
