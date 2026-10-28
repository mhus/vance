package de.mhus.vance.brain.tools.runner;

import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.ToolLabels;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * One {@code runner_*} tool: forwards its parameters to one runner operation and
 * hands the runner's answer back unchanged. The runner already answers in the shape
 * of the matching {@code work_*} tool, so nothing is translated here beyond naming
 * the RootDir and, for a command, the owning process.
 *
 * <p>Deferred and not primary, like the explicit {@code work_*} variants: the generic
 * {@code file_*} / {@code exec_*} tools reach it when the work target is the runner.
 * Whether a project has a runner is a per-project fact, so the tool is always listed
 * and a call without a runner fails with a message saying so.
 */
final class RunnerBackendTool implements Tool {

    /** What the tool does to the world — decides its labels. */
    enum Effect {
        READ(Set.of(ToolLabels.WORKER, "read-only")),
        WRITE(Set.of(ToolLabels.WORKER, "write", "side-effect")),
        EXEC(Set.of(ToolLabels.WORKER, "executive", "side-effect"));

        private final Set<String> labels;

        Effect(Set<String> labels) {
            this.labels = labels;
        }
    }

    private final RunnerWork runner;
    private final String name;
    private final String description;
    private final Map<String, Object> schema;
    private final String op;
    private final Effect effect;
    private final boolean needsRootDir;
    private final boolean ownedByProcess;

    /**
     * @param needsRootDir   the operation works inside a RootDir, so {@code dirName} is
     *     resolved (named, or the process's temp RootDir)
     * @param ownedByProcess the operation takes the calling process as owner of what it starts
     */
    RunnerBackendTool(
            RunnerWork runner,
            String name,
            String description,
            Map<String, Object> schema,
            String op,
            Effect effect,
            boolean needsRootDir,
            boolean ownedByProcess) {
        this.runner = runner;
        this.name = name;
        this.description = description;
        this.schema = schema;
        this.op = op;
        this.effect = effect;
        this.needsRootDir = needsRootDir;
        this.ownedByProcess = ownedByProcess;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public boolean deferred() {
        return true;
    }

    @Override
    public String searchHint() {
        return "Explicit RUNNER variant — targets the project's runner regardless of the work target."
                + " Prefer the generic file_* / exec_* tools with work_target_set(kind=\"RUNNER\").";
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return schema;
    }

    @Override
    public Set<String> labels() {
        return effect.labels;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        Map<String, Object> args = new LinkedHashMap<>(params == null ? Map.of() : params);
        if (needsRootDir) {
            args.put("dirName", runner.rootDir(ctx, stringOrNull(args.get("dirName"))));
        }
        if (ownedByProcess && ctx.processId() != null) {
            args.put("ownerProcessId", ctx.processId());
        }
        return runner.call(ctx, op, args);
    }

    private static @Nullable String stringOrNull(@Nullable Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }
}
