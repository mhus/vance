package de.mhus.vance.brain.tools.worktarget;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component("workTargetFileGrepTool")
public class FileGrepTool extends AbstractWorkTargetTool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "pattern", Map.of(
                            "type", "string",
                            "description", "Regex pattern to search for."),
                    "path", Map.of(
                            "type", "string",
                            "description", "CLIENT only: starting directory."),
                    "glob", Map.of(
                            "type", "string",
                            "description", "Optional file glob."),
                    "dirName", Map.of(
                            "type", "string",
                            "description", "WORK only: override the active RootDir for this call.")),
            "required", List.of("pattern"));

    public FileGrepTool(WorkTargetDispatcher dispatcher) { super(dispatcher); }

    @Override public String name() { return "file_grep"; }
    @Override public String description() {
        return "Recursively grep regex patterns across files at the "
                + "active work target. Dispatches to client_file_grep "
                + "(CLIENT) or work_file_grep (WORK).";
    }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public java.util.Set<String> labels() { return java.util.Set.of("read-only"); }
    @Override protected String clientBackend() { return "client_file_grep"; }
    @Override protected String workBackend()   { return "work_file_grep"; }
}
