package de.mhus.vance.shared.runner;

import de.mhus.vance.shared.project.maintenance.ProjectDataHandler;
import de.mhus.vance.shared.runner.RunnerClient.Reply;
import de.mhus.vance.shared.runner.RunnerClient.RunnerCallException;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * What a project keeps on its runner: RootDirs, jobs and their logs, the scoped
 * {@code HOME}. The one handler whose subject lives on another machine and not in Mongo —
 * so {@link #collections()} is empty and the work goes over the runner protocol.
 *
 * <p><b>An unreachable runner is an error here, never "nothing to delete".</b> The project
 * would be gone while its data lingers on the runner, to be inherited by the next project
 * created under the same name. A project without a runner (none registered for it or its
 * tenant) has nothing to ask, and answers zero.
 *
 * <p>Runs before the project's own record is removed (the record may carry the runner
 * registration) — the project row is the last thing a delete takes.
 */
@Component
@RequiredArgsConstructor
public class RunnerProjectDataHandler implements ProjectDataHandler {

    private static final String ACTING_USER = "_anus";

    private final RunnerClient client;
    private final RunnerBindingService bindings;

    @Override
    public String id() {
        return "runner";
    }

    @Override
    public Set<String> collections() {
        return Set.of();
    }

    @Override
    public int order() {
        return 2310;
    }

    @Override
    public long count(String tenantId, String projectId) {
        if (!hasRunner(tenantId, projectId)) {
            return 0;
        }
        return number(call(tenantId, projectId, "project.info", null), "rootDirs");
    }

    @Override
    public long delete(String tenantId, String projectId) {
        if (!hasRunner(tenantId, projectId)) {
            return 0;
        }
        Map<String, Object> result = call(tenantId, projectId, "project.delete", null);
        return number(result, "rootDirs") + number(result, "jobs");
    }

    @Override
    public long rename(String tenantId, String projectId, String newProjectId) {
        if (!hasRunner(tenantId, projectId)) {
            return 0;
        }
        return number(call(tenantId, projectId, "project.rename", Map.of("newProjectId", newProjectId)), "rewritten");
    }

    /** Refuses while a job of the project still runs on the runner, or the new name holds data there. */
    @Override
    public @Nullable String renameBlocker(String tenantId, String projectId, String newProjectId) {
        if (!hasRunner(tenantId, projectId)) {
            return null;
        }
        Map<String, Object> info = call(tenantId, projectId, "project.info", null);
        if (number(info, "runningJobs") > 0) {
            return "the runner still runs " + number(info, "runningJobs") + " job(s) of '" + projectId
                    + "' — stop them before the rename";
        }
        if (number(call(tenantId, newProjectId, "project.info", null), "rootDirs") > 0) {
            return "the runner already holds RootDirs for '" + newProjectId + "' — merging is not a rename";
        }
        return null;
    }

    @Override
    public @Nullable String deleteNote(String tenantId, String projectId) {
        return hasRunner(tenantId, projectId)
                ? "deletes the project's RootDirs, jobs and home on its runner (jobs still running are killed)"
                : null;
    }

    private boolean hasRunner(String tenantId, String projectId) {
        return bindings.resolve(tenantId, projectId).isPresent();
    }

    private Map<String, Object> call(String tenantId, String projectId, String op, @Nullable Map<String, Object> args) {
        Reply reply;
        try {
            reply = client.call(tenantId, projectId, ACTING_USER, null, op, args, new byte[0]);
        } catch (RunnerCallException e) {
            throw new IllegalStateException(
                    "The runner of project '" + projectId + "' did not answer (" + op + "): " + e.getMessage()
                            + " — the project's data on the runner is untouched; fix the runner and run this again",
                    e);
        }
        if (!reply.ok()) {
            throw new IllegalStateException("The runner of project '" + projectId + "' refused " + op + ": "
                    + reply.header().error().message());
        }
        var result = reply.header().result();
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        if (result != null && result.isObject()) {
            result.properties()
                    .forEach(entry -> out.put(
                            entry.getKey(),
                            entry.getValue().isNumber()
                                    ? (Object) entry.getValue().asLong()
                                    : entry.getValue().asString()));
        }
        return out;
    }

    private static long number(Map<String, Object> result, String key) {
        return result.get(key) instanceof Number n ? n.longValue() : 0;
    }
}
