package de.mhus.vance.brain.tools.worktarget;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Generic file-list. Dispatches to {@code client_file_list} or
 * {@code work_file_list} based on the process's current
 * {@link de.mhus.vance.shared.worktarget.WorkTarget}.
 *
 * <p>Both backends list <b>one</b> directory level and mark directories with
 * a trailing {@code /}. The WORK side used to return every file in the
 * RootDir recursively under a different result key, so the same tool name
 * answered two different questions depending on the target.
 */
@Component("workTargetFileListTool")
public class FileListTool extends AbstractWorkTargetTool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "path", Map.of(
                            "type", "string",
                            "description",
                                    "Directory to list. CLIENT: absolute or working-dir "
                                            + "relative; WORK: relative to the RootDir. "
                                            + "Default: the root."),
                    "dirName", Map.of(
                            "type", "string",
                            "description",
                                    "WORK only: override the active RootDir for this "
                                            + "call. Ignored when the active target is CLIENT.")),
            "required", List.of());

    public FileListTool(WorkTargetDispatcher dispatcher) { super(dispatcher); }

    @Override public String name() { return "file_list"; }
    @Override public String description() {
        return "List one directory level at the active work target. Returns "
                + "entry names, directories marked with a trailing '/'. Use "
                + "file_find for a recursive listing. Dispatches to "
                + "client_file_list (CLIENT) or work_file_list (WORK).";
    }
    @Override public boolean contributesPrak() {
        // Filesystem listing — entries only, no synthesised insight.
        return false;
    }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public java.util.Set<String> labels() { return java.util.Set.of("read-only"); }

    @Override
    public @org.jspecify.annotations.Nullable String troubleshootingHint() {
        return "Not a directory = path names a file, use file_read; only one level "
                + "is listed, use file_find to recurse.";
    }

    @Override protected String clientBackend() { return "client_file_list"; }
    @Override protected String workBackend()   { return "work_file_list"; }
}
