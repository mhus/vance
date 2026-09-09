package de.mhus.vance.brain.tools.worktarget;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component("workTargetFileEditTool")
public class FileEditTool extends AbstractWorkTargetTool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties",
                    Map.of(
                            "path",
                                    Map.of(
                                            "type", "string",
                                            "description", "File path."),
                            "dirName",
                                    Map.of(
                                            "type", "string",
                                            "description", "WORK only: override the active RootDir for this call."),
                            "oldText",
                                    Map.of(
                                            "type", "string",
                                            "description", "Exact snippet to replace. Whitespace-sensitive."),
                            "newText",
                                    Map.of(
                                            "type", "string",
                                            "description", "Replacement text."),
                            "expectedContentHash",
                                    Map.of(
                                            "type",
                                            "string",
                                            "description",
                                            "Optional If-Match guard: the "
                                                    + "contentHash from your last file_read of "
                                                    + "this file. The edit is refused when the "
                                                    + "file changed meanwhile.")),
            "required", List.of("path", "oldText", "newText"));

    public FileEditTool(WorkTargetDispatcher dispatcher) {
        super(dispatcher);
    }

    @Override
    public String name() {
        return "file_edit";
    }

    @Override
    public String description() {
        return "Replace one occurrence of oldText with newText inside a "
                + "file at the active work target. Fails if oldText is "
                + "not found or appears more than once — add surrounding "
                + "context until the match is unique. Pass the contentHash "
                + "from your last file_read as expectedContentHash to refuse "
                + "the edit when the file changed since that read. Dispatches "
                + "to client_file_edit (CLIENT) or work_file_edit (WORK).";
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public java.util.Set<String> labels() {
        return java.util.Set.of("write", "side-effect");
    }

    @Override
    public @org.jspecify.annotations.Nullable String troubleshootingHint() {
        return "Match not unique = expand surrounding context; contentHash mismatch = file changed since "
                + "your read, file_read again; file missing = file_read first.";
    }

    @Override
    public java.util.Set<String> prakLabels() {
        return java.util.Set.of("filesystem");
    }

    @Override
    protected String clientBackend() {
        return "client_file_edit";
    }

    @Override
    protected String workBackend() {
        return "work_file_edit";
    }
}
