package de.mhus.vance.brain.tools.runner;

import de.mhus.vance.shared.runner.RunnerBindingService;
import de.mhus.vance.shared.runner.RunnerClient;
import de.mhus.vance.shared.runner.RunnerClient.Reply;
import de.mhus.vance.shared.runner.RunnerClient.RunnerCallException;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Calls the runner on behalf of a tool: finds the project's runner, turns the answer
 * into the plain map a tool returns, and a refusal into a {@link ToolException} the
 * model can act on. Also settles which runner-side RootDir a call works in.
 */
@Service
@RequiredArgsConstructor
public class RunnerWork {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Marks the temp RootDir of one process on the runner, so later calls find it again. */
    private static final String LABEL_PREFIX = "proc-";

    private final RunnerClient client;
    private final RunnerBindingService bindings;

    /** Whether a runner serves this project (its own, else the tenant's). */
    public boolean available(String tenantId, @Nullable String projectId) {
        return bindings.resolve(tenantId, projectId).isPresent();
    }

    /**
     * Runs {@code op} on the runner of the context's project.
     *
     * @throws ToolException when there is no runner, it cannot be reached, or it refused the call
     */
    public Map<String, Object> call(ToolInvocationContext ctx, String op, Map<String, Object> args) {
        return call(ctx, op, args, new byte[0], null);
    }

    /**
     * As {@link #call(ToolInvocationContext, String, Map)} for an operation that carries
     * text in the body: {@code requestBody} goes out as the request body, and when
     * {@code responseBodyField} is set the answer's body is read as UTF-8 text into that
     * field of the result — file content never rides in the capped JSON header.
     */
    public Map<String, Object> call(
            ToolInvocationContext ctx,
            String op,
            Map<String, Object> args,
            byte[] requestBody,
            @Nullable String responseBodyField) {
        String projectId = requireProject(ctx);
        Reply reply;
        try {
            reply = client.call(
                    ctx.tenantId(),
                    projectId,
                    StringUtils.defaultIfBlank(ctx.userId(), "_agent"),
                    null,
                    op,
                    args,
                    requestBody);
        } catch (RunnerCallException e) {
            throw new ToolException(e.getMessage(), e);
        }
        if (!reply.ok()) {
            throw new ToolException(reply.header().error().message());
        }
        JsonNode result = reply.header().result();
        Map<String, Object> out = new LinkedHashMap<>();
        if (result != null && result.isObject()) {
            result.properties()
                    .forEach(entry -> out.put(entry.getKey(), JSON.convertValue(entry.getValue(), Object.class)));
        }
        if (responseBodyField != null) {
            out.put(responseBodyField, new String(reply.body(), StandardCharsets.UTF_8));
        }
        return out;
    }

    /**
     * The RootDir a file or exec call works in: the one named, else the process's own
     * temp RootDir on the runner — found by its label, created when it is not there yet.
     */
    public String rootDir(ToolInvocationContext ctx, @Nullable String dirName) {
        if (StringUtils.isNotBlank(dirName)) {
            return dirName;
        }
        String processId = ctx.processId();
        if (StringUtils.isBlank(processId)) {
            throw new ToolException(
                    "No 'dirName' given and this call has no process to own a temp RootDir — name the RootDir");
        }
        String label = LABEL_PREFIX + processId;
        Map<String, Object> listed = call(ctx, "root.list", Map.of());
        if (listed.get("rootDirs") instanceof Iterable<?> dirs) {
            for (Object entry : dirs) {
                if (entry instanceof Map<?, ?> dir
                        && label.equals(dir.get("label"))
                        && dir.get("dirName") instanceof String name) {
                    return name;
                }
            }
        }
        Map<String, Object> created =
                call(ctx, "root.create", Map.of("type", "temp", "creatorProcessId", processId, "label", label));
        if (created.get("dirName") instanceof String name) {
            return name;
        }
        throw new ToolException("The runner created a RootDir but did not name it");
    }

    private static String requireProject(ToolInvocationContext ctx) {
        if (StringUtils.isBlank(ctx.projectId())) {
            throw new ToolException("The runner is addressed per project, and this call has none");
        }
        return ctx.projectId();
    }
}
