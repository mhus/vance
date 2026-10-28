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
import java.util.Set;
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
    private static final java.time.Duration DEFAULT_CALL_TIMEOUT = java.time.Duration.ofMinutes(2);

    private static final java.time.Duration INFO_TIMEOUT = java.time.Duration.ofSeconds(3);

    private static final String LABEL_PREFIX = "proc-";

    private final RunnerClient client;
    private final RunnerBindingService bindings;

    /** Whether a runner serves this project (its own, else the tenant's). */
    public boolean available(String tenantId, @Nullable String projectId) {
        return bindings.resolve(tenantId, projectId).isPresent();
    }

    /** The id of the runner serving the project (its own, else the tenant's). */
    public java.util.Optional<String> runnerId(String tenantId, @Nullable String projectId) {
        return bindings.resolve(tenantId, projectId)
                .map(effective -> effective.binding().getRunnerId());
    }

    /**
     * The capability names the project's runner reports in its {@code info}, asked with a short
     * timeout (this sits on the path of listing tools).
     *
     * @throws ToolException when the runner cannot be reached or does not answer
     */
    public Set<String> reportedCapabilities(String tenantId, @Nullable String projectId) {
        Reply reply;
        try {
            reply = client.call(tenantId, projectId, "_agent", null, "info", null, new byte[0], INFO_TIMEOUT);
        } catch (RunnerCallException e) {
            throw new ToolException(e.getMessage(), e);
        }
        JsonNode capabilities = reply.ok() && reply.header().result() != null
                ? reply.header().result().get("capabilities")
                : null;
        if (capabilities == null || !capabilities.isArray()) {
            throw new ToolException("The runner did not report its capabilities");
        }
        Set<String> out = new java.util.LinkedHashSet<>();
        capabilities.forEach(node -> out.add(node.asString()));
        return out;
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
        Binary answer = callBinary(ctx, op, args, requestBody);
        Map<String, Object> out = new LinkedHashMap<>(answer.result());
        if (responseBodyField != null) {
            out.put(responseBodyField, new String(answer.body(), StandardCharsets.UTF_8));
        }
        return out;
    }

    /** As the five-argument {@code call}, waiting up to {@code timeout} for the answer. */
    public Map<String, Object> call(
            ToolInvocationContext ctx,
            String op,
            Map<String, Object> args,
            byte[] requestBody,
            @Nullable String responseBodyField,
            java.time.Duration timeout) {
        Binary answer = callBinary(ctx, op, args, requestBody, timeout);
        Map<String, Object> out = new LinkedHashMap<>(answer.result());
        if (responseBodyField != null) {
            out.put(responseBodyField, new String(answer.body(), StandardCharsets.UTF_8));
        }
        return out;
    }

    /** What a runner answered: the JSON result and the body, as it came. */
    public record Binary(Map<String, Object> result, byte[] body) {}

    /**
     * As {@link #call(ToolInvocationContext, String, Map)} for an operation whose request and
     * answer carry bytes — audio in, audio out.
     *
     * @throws RunnerRefusedException when the runner refused the call, with its code
     */
    public Binary callBinary(ToolInvocationContext ctx, String op, Map<String, Object> args, byte[] requestBody) {
        return callBinary(ctx, op, args, requestBody, null);
    }

    /** As {@link #callBinary(ToolInvocationContext, String, Map, byte[])} with a longer wait, for an operation that takes long. */
    public Binary callBinary(
            ToolInvocationContext ctx,
            String op,
            Map<String, Object> args,
            byte[] requestBody,
            java.time.@Nullable Duration timeout) {
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
                    requestBody,
                    timeout == null ? DEFAULT_CALL_TIMEOUT : timeout);
        } catch (RunnerCallException e) {
            throw new ToolException(e.getMessage(), e);
        }
        if (!reply.ok()) {
            throw new RunnerRefusedException(
                    reply.header().error().code(), reply.header().error().message());
        }
        JsonNode result = reply.header().result();
        Map<String, Object> out = new LinkedHashMap<>();
        if (result != null && result.isObject()) {
            result.properties()
                    .forEach(entry -> out.put(entry.getKey(), JSON.convertValue(entry.getValue(), Object.class)));
        }
        return new Binary(out, reply.body());
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
