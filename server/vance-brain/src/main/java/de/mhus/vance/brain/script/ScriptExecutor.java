package de.mhus.vance.brain.script;

import de.mhus.vance.brain.tools.ContextToolsApi;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Single entry point for server-side JavaScript execution. Implementations
 * build a fresh {@link org.graalvm.polyglot.Context} per run, inject the
 * {@code vance} host object backed by the request's
 * {@link ContextToolsApi}, evaluate the source, and close the context.
 */
public interface ScriptExecutor {

    /**
     * Runs {@code request.code()}. Throws {@link ScriptExecutionException}
     * on any failure (guest error, host error, resource exhaustion,
     * timeout). Returns the script's mapped value otherwise.
     */
    ScriptResult run(ScriptRequest request);

    /**
     * Convenience: read {@code path} with UTF-8 and run it. The path
     * becomes the {@code sourceName} so stack traces show the file.
     * Caller is responsible for verifying that the path is legal —
     * the executor does no path validation.
     */
    default ScriptResult runFile(Path path, ContextToolsApi tools, Duration timeout) {
        String code;
        try {
            code = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ScriptExecutionException(
                    ScriptExecutionException.ErrorClass.HOST_EXCEPTION,
                    "Failed to read script file: " + path,
                    e);
        }
        return run(new ScriptRequest("js", code, path.toString(), tools, timeout));
    }
}
