package de.mhus.vance.brain.tools.worktarget;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Deletion half of the generic file surface. Exists so that deleting is
 * reachable at all: both backends are {@code deferred}, and a deferred
 * tool without a primary wrapper is effectively undiscoverable — {@code
 * how_do_i} and {@code find_tools} are weak substitutes for a name in the
 * manifest. See {@code planning/tool-naming-sweep.md} §6.
 *
 * <p>On {@code WorkTarget.kind == CLIENT} this reaches a file on the
 * user's own machine, gated by the foot sandbox's dedicated
 * {@code delete} rule domain — a path the user allowed for reading does
 * not thereby become deletable ({@code specification/public/foot-sandbox.md}).
 */
@Component("workTargetFileDeleteTool")
public class FileDeleteTool extends AbstractWorkTargetTool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "path", Map.of(
                            "type", "string",
                            "description", "File path. Relative interpretation depends on active work target."),
                    "dirName", Map.of(
                            "type", "string",
                            "description", "WORK only: override the active RootDir for this call.")),
            "required", List.of("path"));

    public FileDeleteTool(WorkTargetDispatcher dispatcher) { super(dispatcher); }

    @Override public String name() { return "file_delete"; }
    @Override public String description() {
        return "Delete a file at the active work target. Safe to call on a "
                + "path that doesn't exist — returns deleted=false. "
                + "Dispatches to client_file_delete (CLIENT) or "
                + "work_file_delete (WORK) transparently. On CLIENT this "
                + "removes a file from the user's own machine and needs "
                + "their sandbox approval.";
    }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public java.util.Set<String> labels() { return java.util.Set.of("write", "side-effect"); }

    @Override
    public @org.jspecify.annotations.Nullable String troubleshootingHint() {
        return "Permission denied = check target/path; CLIENT needs Foot connected "
                + "and a matching delete rule in the user's sandbox policy.";
    }

    @Override
    public java.util.Set<String> prakLabels() {
        return java.util.Set.of("filesystem");
    }

    @Override protected String clientBackend() { return "client_file_delete"; }
    @Override protected String workBackend()   { return "work_file_delete"; }
}
