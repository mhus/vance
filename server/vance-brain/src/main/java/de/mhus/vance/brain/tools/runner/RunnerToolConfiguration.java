package de.mhus.vance.brain.tools.runner;

import de.mhus.vance.brain.tools.runner.RunnerBackendTool.Effect;
import de.mhus.vance.brain.tools.workspace.WorkspaceCountTool;
import de.mhus.vance.brain.tools.workspace.WorkspaceFindTool;
import de.mhus.vance.brain.tools.workspace.WorkspaceGrepTool;
import de.mhus.vance.brain.tools.workspace.WorkspaceHeadTailTool;
import de.mhus.vance.toolpack.Tool;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The {@code runner_*} tools. Each declares the parameters of its {@code work_*} twin —
 * the work-target wrappers reject a parameter the active backend does not declare, so
 * a drift here would surface as an error on the model's call.
 */
@Configuration
class RunnerToolConfiguration {

    private static final String DIR_NAME =
            "RootDir on the runner; omit for this process's temp RootDir " + "(created on first use).";

    @Bean
    Tool runnerRootList(RunnerWork runner) {
        return new RunnerBackendTool(
                runner,
                "runner_root_list",
                "List the RootDirs of this project on its runner: dirName, type, label and, for git/python/node, what"
                        + " the runner recorded (repository, commit, interpreter).",
                schema(List.of()),
                "root.list",
                Effect.READ,
                false,
                false,
                RunnerBackendTool.Body.NONE,
                null);
    }

    @Bean
    Tool runnerRootCreate(RunnerWork runner, GitCredentialResolver credentials) {
        return new RunnerRootCreateTool(runner, credentials);
    }

    @Bean
    Tool runnerRootDelete(RunnerWork runner) {
        return new RunnerBackendTool(
                runner,
                "runner_root_delete",
                "Delete a RootDir of this project on its runner, with everything in it.",
                schema(List.of("dirName"), prop("dirName", "string", "The RootDir to delete.")),
                "root.delete",
                Effect.WRITE,
                false,
                false,
                RunnerBackendTool.Body.NONE,
                null);
    }

    @Bean
    Tool runnerFileRead(RunnerWork runner) {
        return new RunnerBackendTool(
                runner,
                "runner_file_read",
                "Read a text file from a RootDir on the project's runner. Returns content, truncated, totalChars and"
                        + " a contentHash to guard a later edit with.",
                schema(
                        List.of("path"),
                        prop("path", "string", "File path relative to the RootDir."),
                        prop("dirName", "string", DIR_NAME),
                        prop("maxChars", "integer", "Cap on returned characters."),
                        prop("startLine", "integer", "1-based first line to return (reads a line range)."),
                        prop("maxLines", "integer", "Number of lines from startLine.")),
                "file.read",
                Effect.READ,
                true,
                false,
                RunnerBackendTool.Body.CONTENT_IN_RESPONSE,
                null);
    }

    @Bean
    Tool runnerFileWrite(RunnerWork runner) {
        return new RunnerBackendTool(
                runner,
                "runner_file_write",
                "Write a text file in a RootDir on the project's runner, creating parents and overwriting. Pass the"
                        + " contentHash of your last read as expectedContentHash to refuse the write when the file"
                        + " changed meanwhile.",
                schema(
                        List.of("path", "content"),
                        prop("path", "string", "File path relative to the RootDir."),
                        prop("dirName", "string", DIR_NAME),
                        prop("content", "string", "Full file content."),
                        prop("expectedContentHash", "string", "Optional If-Match guard from your last read.")),
                "file.write",
                Effect.WRITE,
                true,
                false,
                RunnerBackendTool.Body.CONTENT_IN_REQUEST,
                null);
    }

    @Bean
    Tool runnerFileEdit(RunnerWork runner) {
        return new RunnerBackendTool(
                runner,
                "runner_file_edit",
                "Replace one occurrence of oldText with newText in a file on the project's runner. Fails if oldText is"
                        + " missing or appears more than once — add context until it is unique.",
                schema(
                        List.of("path", "oldText", "newText"),
                        prop("path", "string", "File path relative to the RootDir."),
                        prop("dirName", "string", DIR_NAME),
                        prop("oldText", "string", "Exact text to replace."),
                        prop("newText", "string", "Replacement text."),
                        prop("expectedContentHash", "string", "Optional If-Match guard from your last read.")),
                "file.edit",
                Effect.WRITE,
                true,
                false,
                RunnerBackendTool.Body.NONE,
                null);
    }

    @Bean
    Tool runnerFileList(RunnerWork runner) {
        return new RunnerBackendTool(
                runner,
                "runner_file_list",
                "List one directory level in a RootDir on the project's runner; directories end in '/'.",
                schema(
                        List.of(),
                        prop("path", "string", "Directory below the RootDir; omit for its root."),
                        prop("dirName", "string", DIR_NAME)),
                "file.list",
                Effect.READ,
                true,
                false,
                RunnerBackendTool.Body.NONE,
                null);
    }

    /*
     * The four searches take the schema of their work_* twin as it stands: the code
     * behind both is RootDirSearch, so one declaration serves both.
     */

    @Bean
    Tool runnerFileFind(RunnerWork runner, WorkspaceFindTool twin) {
        return new RunnerBackendTool(
                runner,
                "runner_file_find",
                "Find files in a RootDir on the project's runner by path glob, size range and modification-time"
                        + " range. Returns relative paths with size and mtime; sort by path (default), mtime or size.",
                twin.paramsSchema(),
                "file.find",
                Effect.READ,
                true,
                false,
                RunnerBackendTool.Body.NONE,
                null);
    }

    @Bean
    Tool runnerFileGrep(RunnerWork runner, WorkspaceGrepTool twin) {
        return new RunnerBackendTool(
                runner,
                "runner_file_grep",
                "Recursively grep a regex across files in a RootDir on the project's runner. Returns matching lines"
                        + " with path and 1-based line number, optionally with context. Binary and oversized files"
                        + " are skipped.",
                twin.paramsSchema(),
                "file.grep",
                Effect.READ,
                true,
                false,
                RunnerBackendTool.Body.NONE,
                null);
    }

    @Bean
    Tool runnerFileCount(RunnerWork runner, WorkspaceCountTool twin) {
        return new RunnerBackendTool(
                runner,
                "runner_file_count",
                "Count lines, characters and bytes of one file or of many files in a RootDir on the project's runner."
                        + " An optional regex narrows the line count to matching lines.",
                twin.paramsSchema(),
                "file.count",
                Effect.READ,
                true,
                false,
                RunnerBackendTool.Body.NONE,
                null);
    }

    @Bean
    Tool runnerFileHeadTail(RunnerWork runner, WorkspaceHeadTailTool twin) {
        return new RunnerBackendTool(
                runner,
                "runner_file_head_tail",
                "Return the first N and/or last N lines of a file on the project's runner, with 1-based line"
                        + " numbers. At least one of head / tail must be > 0.",
                twin.paramsSchema(),
                "file.head_tail",
                Effect.READ,
                true,
                false,
                RunnerBackendTool.Body.NONE,
                null);
    }

    @Bean
    Tool runnerFileDelete(RunnerWork runner) {
        return new RunnerBackendTool(
                runner,
                "runner_file_delete",
                "Delete a file from a RootDir on the project's runner.",
                schema(
                        List.of("path"),
                        prop("path", "string", "File path relative to the RootDir."),
                        prop("dirName", "string", DIR_NAME)),
                "file.delete",
                Effect.WRITE,
                true,
                false,
                RunnerBackendTool.Body.NONE,
                null);
    }

    @Bean
    Tool runnerExecRun(RunnerWork runner, RunnerJobs jobs) {
        return new RunnerBackendTool(
                runner,
                "runner_exec_run",
                "Run a shell command in a RootDir on the project's runner. Waits up to waitMs for it to finish and"
                        + " returns status, exit code and the output; a longer job keeps running — poll it with"
                        + " runner_exec_status / runner_exec_tail.",
                schema(
                        List.of("command"),
                        prop("command", "string", "Shell command."),
                        prop("dirName", "string", DIR_NAME),
                        prop("waitMs", "integer", "How many ms to block waiting for completion."),
                        prop("deadlineSeconds", "integer", "Optional hard-kill deadline (seconds from now).")),
                "exec.start",
                Effect.EXEC,
                true,
                true,
                RunnerBackendTool.Body.NONE,
                jobs);
    }

    @Bean
    Tool runnerExecStatus(RunnerWork runner) {
        return new RunnerBackendTool(
                runner,
                "runner_exec_status",
                "Status and inline output of a command started with runner_exec_run.",
                schema(List.of("id"), prop("id", "string", "Job id returned by runner_exec_run.")),
                "exec.status",
                Effect.READ,
                false,
                false,
                RunnerBackendTool.Body.NONE,
                null);
    }

    @Bean
    Tool runnerExecTail(RunnerWork runner) {
        return new RunnerBackendTool(
                runner,
                "runner_exec_tail",
                "The last N lines (default 10, max 500) of a runner job's stdout or stderr, oldest first.",
                schema(
                        List.of("id"),
                        prop("id", "string", "Job id returned by runner_exec_run."),
                        prop("n", "integer", "Number of lines."),
                        prop("stream", "string", "stdout (default) or stderr.")),
                "exec.tail",
                Effect.READ,
                false,
                false,
                RunnerBackendTool.Body.NONE,
                null);
    }

    @Bean
    Tool runnerExecKill(RunnerWork runner) {
        return new RunnerBackendTool(
                runner,
                "runner_exec_kill",
                "Force-kill a still-running runner job. Returns killed=false if it already finished.",
                schema(List.of("id"), prop("id", "string", "Job id returned by runner_exec_run.")),
                "exec.kill",
                Effect.EXEC,
                false,
                false,
                RunnerBackendTool.Body.NONE,
                null);
    }

    private static Map.Entry<String, Object> prop(String name, String type, String description) {
        return Map.entry(name, Map.of("type", type, "description", description));
    }

    @SafeVarargs
    private static Map<String, Object> schema(List<String> required, Map.Entry<String, Object>... properties) {
        Map<String, Object> props = new LinkedHashMap<>();
        for (Map.Entry<String, Object> property : properties) {
            props.put(property.getKey(), property.getValue());
        }
        return Map.of("type", "object", "properties", props, "required", required);
    }
}
