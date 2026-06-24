package de.mhus.vance.brain.tools.worktarget;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component("workTargetFileHeadTailTool")
public class FileHeadTailTool extends AbstractWorkTargetTool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "path", Map.of(
                            "type", "string",
                            "description", "File path."),
                    "head", Map.of(
                            "type", "integer",
                            "description", "Number of lines from the start."),
                    "tail", Map.of(
                            "type", "integer",
                            "description", "Number of lines from the end."),
                    "dirName", Map.of(
                            "type", "string",
                            "description", "WORK only: override the active RootDir for this call.")),
            "required", List.of("path"));

    public FileHeadTailTool(WorkTargetDispatcher dispatcher) { super(dispatcher); }

    @Override public String name() { return "file_head_tail"; }
    @Override public String description() {
        return "Return the first N lines (head) and / or last N lines "
                + "(tail) of a file at the active work target. "
                + "Dispatches to client_file_head_tail (CLIENT) or "
                + "work_file_head_tail (WORK).";
    }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public java.util.Set<String> labels() { return java.util.Set.of("read-only"); }

    @Override
    public java.util.Set<String> prakLabels() {
        return java.util.Set.of("filesystem");
    }

    @Override protected String clientBackend() { return "client_file_head_tail"; }
    @Override protected String workBackend()   { return "work_file_head_tail"; }
}
