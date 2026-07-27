package de.mhus.vance.addon.brain.rlang;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.rosuda.REngine.REXP;
import org.rosuda.REngine.RList;
import org.rosuda.REngine.Rserve.RConnection;
import org.springframework.stereotype.Service;

/**
 * The shared Rserve eval-core. Opens a connection to the Rserve daemon,
 * {@code setwd()}s into a caller-supplied working dir, evaluates the R script
 * verbatim, captures {@code print()}/{@code cat()} stdout plus the final
 * expression's value, and diffs the working dir for files the run produced.
 *
 * <p>This is deliberately consumer-agnostic (tex-pattern): it does <em>not</em>
 * manage temp dirs, import documents, or emit progress. Two consumers share it:
 * <ul>
 *   <li>{@link RScriptTool} — mints/cleans a temp dir and imports the produced
 *       files as Vance documents;</li>
 *   <li>{@code RDamogranTask} — evaluates against a provisioned compose
 *       workspace (WORK) and surfaces the produced files as workspace
 *       artifacts.</li>
 * </ul>
 *
 * <p>Every runtime failure (daemon unavailable, socket dead, {@code setwd}
 * failure, R-level error) comes back as {@link Result#ok()} {@code == false}
 * with a human-readable {@link Result#errorMessage()} — the service never
 * throws for these. Each consumer decides how to surface it (tool → throws
 * {@code ToolException}; task → returns a failure result).
 */
@Service
@Slf4j
public class RExecutionService {

    /** Truncation budget for the captured stdout, in characters. */
    static final int MAX_OUTPUT_CHARS = 50_000;

    private final RserveHealth health;
    private final RserveDaemonManager daemonManager;

    public RExecutionService(RserveHealth health, RserveDaemonManager daemonManager) {
        this.health = health;
        this.daemonManager = daemonManager;
    }

    /**
     * Evaluate {@code script} with {@code workingDir} as the R session's cwd.
     * The dir must already exist (the caller owns its lifecycle). Files that
     * appear in the top level of {@code workingDir} during the run are returned
     * in {@link Result#newFiles()} as absolute paths.
     */
    public Result evaluate(String script, Path workingDir) {
        // Lazy daemon start: on first call (or after a crash), this spawns
        // `R CMD Rserve` and blocks until the port answers; a healthy daemon
        // short-circuits immediately.
        try {
            daemonManager.ensureRunning();
        } catch (RuntimeException e) {
            return Result.failure("Rserve daemon unavailable: " + e.getMessage());
        }

        Set<String> preExisting = snapshot(workingDir);
        long started = System.currentTimeMillis();
        RConnection c;
        try {
            c = new RConnection(health.properties().getHost(), health.properties().getPort());
        } catch (Exception e) {
            return Result.failure("Could not open Rserve connection: " + e.getMessage());
        }

        try {
            c.assign("vance_workdir", workingDir.toString());
            REXP setOk = c.eval(
                    "tryCatch({"
                            + " setwd(vance_workdir);"
                            + " list(ok=TRUE, dir=getwd())"
                            + "}, error=function(e) list(ok=FALSE, "
                            + "message=conditionMessage(e)))");
            RList sl = setOk.asList();
            if (sl.at("ok").asInteger() != 1) {
                return Result.failure(
                        "setwd('" + workingDir + "') failed: " + sl.at("message").asString());
            }

            // Ship the script as a string variable and source() it, so the
            // agent never has to escape quotes/newlines/dollar-signs. Stdout is
            // captured; R errors are caught and returned as status='error'.
            c.assign("vance_script", script);
            REXP result = c.eval(
                    "tryCatch({"
                            + " .vance_out <- capture.output("
                            + "   .vance_value <- "
                            + "     source(textConnection(vance_script),"
                            + "            echo=FALSE, max.deparse.length=Inf)$value"
                            + " );"
                            + " list(status='ok',"
                            + "      output=paste(.vance_out, collapse='\\n'),"
                            + "      value=if (is.null(.vance_value)) ''"
                            + "            else paste(capture.output(print(.vance_value)),"
                            + "                       collapse='\\n'))"
                            + "}, error=function(e) list(status='error', "
                            + "                          message=conditionMessage(e)))");

            RList r = result.asList();
            if (!"ok".equals(r.at("status").asString())) {
                return Result.failure("R error: " + r.at("message").asString());
            }

            String combined = combine(r.at("output").asString(), r.at("value").asString());
            int fullLen = combined.length();
            boolean truncated = fullLen > MAX_OUTPUT_CHARS;
            String body = truncated ? combined.substring(0, MAX_OUTPUT_CHARS) : combined;
            double elapsedSec = (System.currentTimeMillis() - started) / 1000.0;

            List<Path> newFiles = discoverNewFiles(workingDir, preExisting);
            return Result.success(body, fullLen, truncated, elapsedSec,
                    health.version() == null ? "unknown" : health.version(), newFiles);
        } catch (Exception e) {
            return Result.failure("Rserve communication failed: " + e.getMessage());
        } finally {
            try {
                c.close();
            } catch (Exception e) {
                log.warn("Could not close Rserve connection cleanly: {}", e.getMessage());
            }
        }
    }

    /**
     * Take a flat snapshot (filename-only) of the working dir, so we can diff
     * against it after the R script ran. Empty when the dir doesn't exist yet.
     */
    private static Set<String> snapshot(Path dir) {
        if (!Files.isDirectory(dir)) {
            return Set.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            Set<String> names = new HashSet<>();
            stream.forEach(p -> names.add(p.getFileName().toString()));
            return names;
        } catch (IOException e) {
            log.warn("Could not snapshot working dir {}: {}", dir, e.getMessage());
            return Set.of();
        }
    }

    /** Top-level regular files that weren't in {@code preExisting}, sorted by name. */
    private static List<Path> discoverNewFiles(Path dir, Set<String> preExisting) {
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> newFiles = new ArrayList<>();
            stream
                    .filter(Files::isRegularFile)
                    .filter(p -> !preExisting.contains(p.getFileName().toString()))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(newFiles::add);
            return newFiles;
        } catch (IOException e) {
            log.warn("Could not list outputs in {}: {}", dir, e.getMessage());
            return List.of();
        }
    }

    /**
     * Glue {@code output} (stdout) and {@code value} (final expression printed) —
     * omit either when empty so we don't emit dangling separator lines.
     */
    static String combine(@Nullable String output, @Nullable String value) {
        String o = output == null ? "" : output.strip();
        String v = value == null ? "" : value.strip();
        if (o.isEmpty() && v.isEmpty()) {
            return "";
        }
        if (o.isEmpty()) {
            return v;
        }
        if (v.isEmpty()) {
            return o;
        }
        // The trailing value gets a separator so the LLM can tell the
        // print()-stream apart from the final value of the script.
        return o + "\n" + v;
    }

    /**
     * Outcome of an R evaluation. A runtime failure rides {@code ok=false} +
     * {@code errorMessage} (the service never throws for these); {@code text}
     * then carries whatever was captured before the failure (usually empty).
     *
     * @param ok             whether the script evaluated cleanly
     * @param errorMessage   failure detail, {@code null} on success
     * @param text           combined + truncated stdout/value
     * @param contentLength  full length of the combined text before truncation
     * @param truncated      whether {@code text} was cut at {@link #MAX_OUTPUT_CHARS}
     * @param elapsedSec     wall-clock eval time in seconds
     * @param rVersion       reported R version, {@code null} on failure
     * @param newFiles       absolute paths of files the run produced in the working dir
     */
    public record Result(
            boolean ok,
            @Nullable String errorMessage,
            String text,
            int contentLength,
            boolean truncated,
            double elapsedSec,
            @Nullable String rVersion,
            List<Path> newFiles) {

        static Result failure(String message) {
            return new Result(false, message, "", 0, false, 0.0, null, List.of());
        }

        static Result success(String text, int contentLength, boolean truncated,
                              double elapsedSec, String rVersion, List<Path> newFiles) {
            return new Result(true, null, text, contentLength, truncated, elapsedSec,
                    rVersion, List.copyOf(newFiles));
        }
    }
}
