package de.mhus.vance.brain.tools.worktarget;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component("workTargetFileWriteTool")
public class FileWriteTool extends AbstractWorkTargetTool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "path", Map.of(
                            "type", "string",
                            "description", "File path. Relative interpretation depends on active work target."),
                    "dirName", Map.of(
                            "type", "string",
                            "description", "WORK only: override the active RootDir for this call."),
                    "content", Map.of(
                            "type", "string",
                            "description", "Full file content. Replaces any existing content.")),
            "required", List.of("path", "content"));

    public FileWriteTool(WorkTargetDispatcher dispatcher) { super(dispatcher); }

    @Override public String name() { return "file_write"; }
    @Override public String description() {
        return "Create or overwrite a text file at the active work target. "
                + "Dispatches to client_file_write (CLIENT) or "
                + "work_file_write (WORK) transparently.";
    }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public java.util.Set<String> labels() { return java.util.Set.of("write", "side-effect"); }
    @Override protected String clientBackend() { return "client_file_write"; }
    @Override protected String workBackend()   { return "work_file_write"; }
}
