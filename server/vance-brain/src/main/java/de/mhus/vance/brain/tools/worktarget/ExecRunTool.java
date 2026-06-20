package de.mhus.vance.brain.tools.worktarget;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component("workTargetExecRunTool")
public class ExecRunTool extends AbstractWorkTargetTool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "command", Map.of(
                            "type", "string",
                            "description", "Shell command to run. Full shell syntax allowed."),
                    "dirName", Map.of(
                            "type", "string",
                            "description",
                                    "WORK only: override the RootDir used as cwd. "
                                            + "Ignored when active target is CLIENT (Foot picks "
                                            + "its own --workdir as cwd)."),
                    "waitMs", Map.of(
                            "type", "integer",
                            "description", "How many ms to block waiting for completion."),
                    "deadlineSeconds", Map.of(
                            "type", "integer",
                            "description", "Optional hard-kill deadline (seconds from now).")),
            "required", List.of("command"));

    public ExecRunTool(WorkTargetDispatcher dispatcher) { super(dispatcher); }

    @Override public String name() { return "exec_run"; }
    @Override public String description() {
        return "Execute a shell command at the active work target. CWD "
                + "is the Foot --workdir for CLIENT or the workspace "
                + "RootDir for WORK — the active target picks where it "
                + "runs. Dispatches to client_exec_run or work_exec_run.";
    }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public java.util.Set<String> labels() { return java.util.Set.of("executive", "side-effect"); }
    @Override protected String clientBackend() { return "client_exec_run"; }
    @Override protected String workBackend()   { return "work_exec_run"; }
}
