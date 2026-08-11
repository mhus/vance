package de.mhus.vance.brain.tools.worktarget;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Generic file-read. Dispatches to {@code client_file_read} or
 * {@code work_file_read} based on the process's current
 * {@link de.mhus.vance.shared.worktarget.WorkTarget}.
 */
@Component("workTargetFileReadTool")
public class FileReadTool extends AbstractWorkTargetTool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "path", Map.of(
                            "type", "string",
                            "description",
                                    "File path. Interpretation depends on the active "
                                            + "work target: for CLIENT, absolute or "
                                            + "Foot --workdir relative; for WORK, "
                                            + "relative to the RootDir."),
                    "dirName", Map.of(
                            "type", "string",
                            "description",
                                    "Optional. Overrides the WORK target's "
                                            + "RootDir for this call. Ignored when "
                                            + "the active target is CLIENT."),
                    "maxChars", Map.of(
                            "type", "integer",
                            "description",
                                    "Maximum characters to return. 0 or negative "
                                            + "means use the server default cap."),
                    "startLine", Map.of(
                            "type", "integer",
                            "description",
                                    "1-based first line to return. Omit to start "
                                            + "at the beginning."),
                    "maxLines", Map.of(
                            "type", "integer",
                            "description",
                                    "Maximum number of lines to return. Combine "
                                            + "with startLine to page through a file "
                                            + "larger than the char cap — raising "
                                            + "maxChars alone cannot reach past the "
                                            + "start of the file.")),
            "required", List.of("path"));

    public FileReadTool(WorkTargetDispatcher dispatcher) {
        super(dispatcher);
    }

    @Override public String name() { return "file_read"; }
    @Override public String description() {
        return "Read a text file from the active work target. Dispatches "
                + "to client_file_read (CLIENT) or work_file_read (WORK) "
                + "transparently — the recipe picks the backend, you just "
                + "call this with a path.";
    }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public java.util.Set<String> labels() { return java.util.Set.of("read-only"); }

    @Override
    public @org.jspecify.annotations.Nullable String troubleshootingHint() {
        return "File missing = check path; CLIENT target needs Foot connected; "
                + "truncated=true = page on with startLine + maxLines (re-reading "
                + "with a bigger maxChars returns the same prefix).";
    }

    @Override
    public java.util.Set<String> prakLabels() {
        return java.util.Set.of("filesystem");
    }

    @Override protected String clientBackend() { return "client_file_read"; }
    @Override protected String workBackend()   { return "work_file_read"; }
}
