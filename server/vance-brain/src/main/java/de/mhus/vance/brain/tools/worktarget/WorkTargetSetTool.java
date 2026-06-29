package de.mhus.vance.brain.tools.worktarget;

import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.worktarget.WorkTarget;
import de.mhus.vance.shared.worktarget.WorkTargetKind;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Switches the calling process's {@link WorkTarget}. Persistent —
 * future turns see the new value via {@link WorkTargetService}.
 *
 * <p>The single {@code targetName} parameter is kind-dependent:
 * for {@code kind=WORK} it picks the RootDir (omit for the process's
 * lazy temp RootDir), for {@code kind=DAEMON} it is the required name
 * of a {@code profile=daemon} Foot in the project, and for
 * {@code kind=CLIENT} it is ignored.
 *
 * <p>The tool does NOT block the switch when the target backend is
 * not currently reachable (no Foot bound, daemon offline) — the caller
 * may be setting up the target for a later turn. The
 * {@code work_target_get} response signals reachability separately.
 */
@Component
@RequiredArgsConstructor
public class WorkTargetSetTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "kind", Map.of(
                            "type", "string",
                            "enum", List.of("CLIENT", "WORK", "DAEMON"),
                            "description",
                                    "Backend to dispatch generic file_* / "
                                            + "exec_* tools to. CLIENT = the "
                                            + "session-bound Foot CLI on the "
                                            + "user's host; WORK = Brain-server "
                                            + "workspace RootDir; DAEMON = a "
                                            + "named profile=daemon Foot in this "
                                            + "project."),
                    "targetName", Map.of(
                            "type", "string",
                            "description",
                                    "Kind-dependent name. WORK: which RootDir "
                                            + "to use (omit for the process's "
                                            + "temp RootDir, lazy-created on "
                                            + "first use). DAEMON: the daemon "
                                            + "name (required). Ignored when "
                                            + "kind=CLIENT.")),
            "required", List.of("kind"));

    private final WorkTargetService workTargetService;
    private final ThinkProcessService thinkProcessService;

    @Override
    public String name() {
        return "work_target_set";
    }

    @Override
    public String description() {
        return "Switch where the generic file_* / exec_* tools dispatch "
                + "to. Persists on the process; subsequent turns see the "
                + "new target. The recipe sets the default — only use "
                + "this for the rare case the LLM needs an explicit "
                + "switch mid-task.";
    }

    @Override
    public boolean primary() {
        // Not primary — the work target normally comes from the recipe
        // and the LLM doesn't switch live. Reachable via find_tools
        // when an exotic switch is genuinely needed.
        return false;
    }

    @Override
    public boolean contributesPrak() {
        // Internal routing state — not user-facing knowledge.
        return false;
    }

    @Override
    public boolean deferred() {
        // Same rationale as WorkTargetGetTool#deferred: stay out of the
        // classified-engine primary manifest. The recipe owns the
        // initial target; explicit mid-task switches are rare enough to
        // earn the find_tools / describe_tool detour.
        return true;
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
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        Object rawKind = params == null ? null : params.get("kind");
        if (!(rawKind instanceof String kindStr) || kindStr.isBlank()) {
            throw new ToolException("'kind' is required (CLIENT, WORK or DAEMON)");
        }
        WorkTargetKind kind;
        try {
            kind = WorkTargetKind.valueOf(kindStr.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ToolException(
                    "Unknown kind '" + kindStr + "' — expected CLIENT, WORK or DAEMON");
        }
        String targetName = null;
        Object rawName = params == null ? null : params.get("targetName");
        if (rawName instanceof String s && !s.isBlank()) {
            targetName = s;
        }
        WorkTarget next = switch (kind) {
            case WORK -> WorkTarget.work(targetName);
            case CLIENT -> WorkTarget.client();
            case DAEMON -> {
                if (targetName == null) {
                    throw new ToolException(
                            "'targetName' (the daemon name) is required when kind=DAEMON");
                }
                yield WorkTarget.daemon(targetName);
            }
        };

        ThinkProcessDocument process = thinkProcessService.findById(ctx.processId())
                .orElseThrow(() -> new ToolException(
                        "work_target_set: process '" + ctx.processId() + "' not found"));
        WorkTarget previous = workTargetService.current(process);
        workTargetService.set(process.getId(), next);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("previous", previous.toMap());
        out.put("current", next.toMap());
        return out;
    }
}
