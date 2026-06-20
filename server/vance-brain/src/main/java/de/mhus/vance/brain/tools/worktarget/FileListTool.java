package de.mhus.vance.brain.tools.worktarget;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class FileListTool extends AbstractWorkTargetTool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "path", Map.of(
                            "type", "string",
                            "description", "CLIENT only: directory to list. Defaults to working dir."),
                    "dirName", Map.of(
                            "type", "string",
                            "description", "WORK only: override the active RootDir for this call.")),
            "required", List.of());

    public FileListTool(WorkTargetDispatcher dispatcher) { super(dispatcher); }

    @Override public String name() { return "file_list"; }
    @Override public String description() {
        return "List files at the active work target. Dispatches to "
                + "client_file_list (CLIENT) or work_file_list (WORK).";
    }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public java.util.Set<String> labels() { return java.util.Set.of("read-only"); }
    @Override protected String clientBackend() { return "client_file_list"; }
    @Override protected String workBackend()   { return "work_file_list"; }
}
