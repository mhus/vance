package de.mhus.vance.brain.tools.worktarget;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class FileCountTool extends AbstractWorkTargetTool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "path", Map.of(
                            "type", "string",
                            "description", "File path or glob."),
                    "dirName", Map.of(
                            "type", "string",
                            "description", "WORK only: override the active RootDir for this call.")),
            "required", List.of("path"));

    public FileCountTool(WorkTargetDispatcher dispatcher) { super(dispatcher); }

    @Override public String name() { return "file_count"; }
    @Override public String description() {
        return "Count lines, characters, and bytes for one or more files "
                + "at the active work target. Dispatches to "
                + "client_file_count (CLIENT) or work_file_count (WORK).";
    }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public java.util.Set<String> labels() { return java.util.Set.of("read-only"); }
    @Override protected String clientBackend() { return "client_file_count"; }
    @Override protected String workBackend()   { return "work_file_count"; }
}
