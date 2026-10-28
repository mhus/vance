package de.mhus.vance.brain.tools.runner;

import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.ToolLabels;
import java.nio.charset.StandardCharsets;
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
    private final Body body;

    /**
     * Where the text of a file travels. The JSON header of a call is capped (1 MiB), so a
     * file is the request's or the answer's <em>body</em>, never a header field.
     */
    enum Body {
        /** Nothing in the body. */
        NONE,
        /** The {@code content} parameter is sent as the request body. */
        CONTENT_IN_REQUEST,
        /** The answer's body is returned as {@code content}. */
        CONTENT_IN_RESPONSE
    }

    /**
     * @param needsRootDir   the operation works inside a RootDir, so {@code dirName} is
     *     resolved (named, or the process's temp RootDir)
     * @param ownedByProcess the operation takes the calling process as owner of what it starts
     * @param body           whether file content travels as request or response body
     */
    RunnerBackendTool(
            RunnerWork runner,
            String name,
            String description,
            Map<String, Object> schema,
            String op,
            Effect effect,
            boolean needsRootDir,
            boolean ownedByProcess,
            Body body) {
        this.runner = runner;
        this.name = name;
        this.description = description;
        this.schema = schema;
        this.op = op;
        this.effect = effect;
        this.needsRootDir = needsRootDir;
        this.ownedByProcess = ownedByProcess;
        this.body = body;
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
        switch (body) {
            case CONTENT_IN_REQUEST -> {
                Object content = args.remove("content");
                if (!(content instanceof String text)) {
                    throw new ToolException("'content' is required and must be a string");
                }
                args.put("contentInBody", true);
                return runner.call(ctx, op, args, text.getBytes(StandardCharsets.UTF_8), null);
            }
            case CONTENT_IN_RESPONSE -> {
                args.put("contentInBody", true);
                return runner.call(ctx, op, args, new byte[0], "content");
            }
            default -> {
                return runner.call(ctx, op, args);
            }
        }
    }

    private static @Nullable String stringOrNull(@Nullable Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }
}
