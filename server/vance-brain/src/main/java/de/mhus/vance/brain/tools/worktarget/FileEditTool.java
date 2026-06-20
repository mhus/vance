package de.mhus.vance.brain.tools.worktarget;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class FileEditTool extends AbstractWorkTargetTool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "path", Map.of(
                            "type", "string",
                            "description", "File path."),
                    "dirName", Map.of(
                            "type", "string",
                            "description", "WORK only: override the active RootDir for this call."),
                    "oldText", Map.of(
                            "type", "string",
                            "description", "Exact snippet to replace. Whitespace-sensitive."),
                    "newText", Map.of(
                            "type", "string",
                            "description", "Replacement text.")),
            "required", List.of("path", "oldText", "newText"));

    public FileEditTool(WorkTargetDispatcher dispatcher) { super(dispatcher); }

    @Override public String name() { return "file_edit"; }
    @Override public String description() {
        return "Replace one occurrence of oldText with newText inside a "
                + "file at the active work target. Fails if oldText is "
                + "not found or appears more than once — add surrounding "
                + "context until the match is unique. Dispatches to "
                + "client_file_edit (CLIENT) or work_file_edit (WORK).";
    }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public java.util.Set<String> labels() { return java.util.Set.of("write", "side-effect"); }
    @Override protected String clientBackend() { return "client_file_edit"; }
    @Override protected String workBackend()   { return "work_file_edit"; }
}
