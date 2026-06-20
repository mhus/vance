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
 * <p>For {@code kind=WORK}, {@code dirName} is optional: omitting
 * it resolves to the process's lazy temp RootDir on the next
 * {@code file_*} / {@code exec_*} call.
 *
 * <p>For {@code kind=CLIENT}, {@code dirName} is ignored. The tool
 * does NOT block the switch when no Foot client is currently
 * connected — the caller may be setting up the target for a
 * later turn. The {@code work_target_get} response signals
 * connectivity separately.
 */
@Component
@RequiredArgsConstructor
public class WorkTargetSetTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "kind", Map.of(
                            "type", "string",
                            "enum", List.of("CLIENT", "WORK"),
                            "description",
                                    "Backend to dispatch generic file_* / "
                                            + "exec_* tools to. CLIENT = Foot "
                                            + "CLI on the user's host; WORK = "
                                            + "Brain-server workspace RootDir."),
                    "dirName", Map.of(
                            "type", "string",
                            "description",
                                    "WORK only: which RootDir to use. Omit "
                                            + "for the process's temp RootDir "
                                            + "(lazy-created on first use). "
                                            + "Ignored when kind=CLIENT.")),
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
                + "new target. Use work_target_get first to learn what "
                + "alternatives are available.";
    }

    @Override
    public boolean primary() {
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
            throw new ToolException("'kind' is required (CLIENT or WORK)");
        }
        WorkTargetKind kind;
        try {
            kind = WorkTargetKind.valueOf(kindStr.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ToolException("Unknown kind '" + kindStr + "' — expected CLIENT or WORK");
        }
        String dirName = null;
        Object rawDir = params == null ? null : params.get("dirName");
        if (rawDir instanceof String s && !s.isBlank()) {
            dirName = s;
        }
        WorkTarget next = kind == WorkTargetKind.WORK
                ? WorkTarget.work(dirName)
                : WorkTarget.client();

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
