package de.mhus.vance.brain.tools.worktarget;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component("workTargetFileFindTool")
public class FileFindTool extends AbstractWorkTargetTool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "glob", Map.of(
                            "type", "string",
                            "description", "Glob pattern (e.g. **/*.java)."),
                    "dirName", Map.of(
                            "type", "string",
                            "description", "WORK only: override the active RootDir for this call.")),
            "required", List.of());

    public FileFindTool(WorkTargetDispatcher dispatcher) { super(dispatcher); }

    @Override public String name() { return "file_find"; }
    @Override public String description() {
        return "Find files at the active work target by path glob. "
                + "Dispatches to client_file_find (CLIENT) or "
                + "work_file_find (WORK).";
    }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public java.util.Set<String> labels() { return java.util.Set.of("read-only"); }
    @Override protected String clientBackend() { return "client_file_find"; }
    @Override protected String workBackend()   { return "work_file_find"; }
}
