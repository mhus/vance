package de.mhus.vance.runner;

import de.mhus.vance.exec.RootDirException;
import de.mhus.vance.runner.RunnerOperation.OperationException;
import de.mhus.vance.runner.RunnerOperation.OperationResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * {@code project.info}, {@code project.delete}, {@code project.rename} — what the admin
 * shell asks of a runner when a project is deleted or renamed: everything the runner holds
 * for the project (its RootDirs, its jobs and their logs, its {@code HOME}), as one unit.
 *
 * <p>The project is the one the token names; {@code project.rename} takes the new name as an
 * argument. A token pinned to one RootDir is refused: this is the whole project's data.
 */
final class ProjectOperations {

    private ProjectOperations() {}

    static List<RunnerOperation> all(RunnerWorkspace workspace) {
        return List.of(info(workspace), delete(workspace), rename(workspace));
    }

    private static RunnerOperation info(RunnerWorkspace workspace) {
        return RootDirOperations.operation("project.info", call -> {
            requireWholeProject(call);
            var claims = call.claims();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put(
                    "rootDirs",
                    workspace
                            .store()
                            .listRootDirs(claims.tenantId(), claims.projectId())
                            .size());
            out.put("runningJobs", runningJobs(workspace, call));
            return OperationResult.of(out);
        });
    }

    /** Kills the project's jobs, removes its RootDirs, job logs and {@code HOME}. Idempotent. */
    private static RunnerOperation delete(RunnerWorkspace workspace) {
        return RootDirOperations.operation("project.delete", call -> {
            requireWholeProject(call);
            var claims = call.claims();
            int rootDirs = workspace
                    .store()
                    .listRootDirs(claims.tenantId(), claims.projectId())
                    .size();
            int killed = workspace.jobs().killAll(scope(call));
            int jobs = workspace.jobs().forgetScope(scope(call));
            workspace.store().dispose(claims.tenantId(), claims.projectId());
            deleteTree(workspace.home(claims.tenantId(), claims.projectId()));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("rootDirs", rootDirs);
            out.put("jobs", jobs);
            out.put("killedJobs", killed);
            return OperationResult.of(out);
        });
    }

    /**
     * Moves the project's RootDirs and {@code HOME} to the new name. A project with a running job
     * is refused — the job's process has the old name in its paths — and so is a new name that
     * already holds a workspace. The job history is dropped; the jobs themselves have ended.
     */
    private static RunnerOperation rename(RunnerWorkspace workspace) {
        return RootDirOperations.operation("project.rename", call -> {
            requireWholeProject(call);
            var claims = call.claims();
            String newProject = new OperationArgs(call.args()).text("newProjectId");
            if (runningJobs(workspace, call) > 0) {
                throw new OperationException(
                        "jobs-running",
                        "Project '" + claims.projectId()
                                + "' has running jobs on this runner — stop them before a rename");
            }
            // The store validates the name; it must be checked before the name becomes a path.
            boolean taken;
            try {
                taken = workspace.store().exists(claims.tenantId(), newProject);
            } catch (RootDirException e) {
                throw new OperationException("bad-name", e.getMessage(), e);
            }
            Path newHome = workspace.home(claims.tenantId(), newProject);
            if (taken || Files.exists(newHome)) {
                throw new OperationException(
                        "name-taken",
                        "This runner already holds data for project '" + newProject + "' — merging is not a rename");
            }
            // HOME moves first and moves back when the workspace move fails, so a failed
            // rename leaves the project whole under its old name and a retry can succeed.
            Path oldHome = workspace.home(claims.tenantId(), claims.projectId());
            moveTree(oldHome, newHome);
            long rewritten;
            try {
                rewritten = workspace.store().rename(claims.tenantId(), claims.projectId(), newProject);
            } catch (RootDirException e) {
                moveTree(newHome, oldHome);
                throw new OperationException("rename-failed", e.getMessage(), e);
            }
            workspace.jobs().forgetScope(scope(call));
            return OperationResult.of(Map.of("rewritten", rewritten));
        });
    }

    private static void requireWholeProject(RunnerOperation.Call call) throws OperationException {
        if (call.claims().rootDir() != null) {
            throw new OperationException(
                    "root-not-allowed", "This call is confined to one RootDir, not to its project");
        }
    }

    private static String scope(RunnerOperation.Call call) {
        return call.claims().tenantId() + "/" + call.claims().projectId();
    }

    private static long runningJobs(RunnerWorkspace workspace, RunnerOperation.Call call) {
        return workspace.jobs().list(scope(call)).stream()
                .filter(job -> !job.isTerminal())
                .count();
    }

    private static void moveTree(Path from, Path to) throws OperationException {
        if (!Files.exists(from)) {
            return;
        }
        try {
            Files.createDirectories(to.getParent());
            Files.move(from, to);
        } catch (IOException e) {
            throw new OperationException(
                    "rename-failed", "Cannot move " + from + " → " + to + ": " + e.getMessage(), e);
        }
    }

    private static void deleteTree(Path dir) throws OperationException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (IOException | UncheckedIOException e) {
            throw new OperationException("delete-failed", "Cannot remove " + dir + ": " + e.getMessage(), e);
        }
    }
}
