package de.mhus.vance.brain.tools.tex;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Strategy interface for compiling a set of LaTeX source files to PDF.
 *
 * <p>Implementations are responsible for the actual compilation only —
 * manifest parsing, file transport (including cross-project references),
 * and PDF import as a document are handled by {@link TexService}.
 *
 * <p>The executor receives a {@link Request} with a workspace root
 * directory already populated with all source files (same-project and
 * cross-project references resolved by the caller). The executor
 * compiles {@code mainDocument} and returns the result.
 *
 * <p>Known implementations:
 * <ul>
 *   <li>{@link Tex2PdfLocalExecutor} — runs {@code latexmk} via
 *       {@link ProcessBuilder} on the local machine (dev/CI, no Docker)</li>
 *   <li>{@link Tex2PdfRbehzadanExecutor} — sends a ZIP to the
 *       rbehzadan/tex2pdf REST service and polls for the result</li>
 * </ul>
 *
 * <p>Selection is via the {@code tex.executor} setting
 * (Project → {@code _tenant} cascade), falling back to
 * {@code vance.tex.executor} application property.
 */
public interface Tex2PdfExecutor {

    /**
     * Type identifier used to match against the {@code tex.executor}
     * setting. Examples: {@code "local"}, {@code "rbehzadan"}.
     */
    String type();

    /**
     * Compile the LaTeX sources in the given workspace root directory.
     *
     * @param request the compilation request
     * @return the compilation result (success or failure with log)
     */
    Result compile(Request request);

    /**
     * Compilation request. The {@code workspaceRoot} is already
     * populated with all source files by the caller. The tenant/project/
     * process context is included so executors can read settings via
     * the cascade (e.g. API keys, service URLs).
     */
    record Request(
            String mainDocument,
            String engine,
            Path workspaceRoot,
            String tenantId,
            @Nullable String projectId,
            @Nullable String processId) {

        /**
         * Resolved engine name, defaulting to {@code pdflatex}.
         */
        public String effectiveEngine() {
            return (engine == null || engine.isBlank()) ? "pdflatex" : engine.trim();
        }
    }

    /**
     * Compilation result.
     */
    record Result(
            boolean success,
            @Nullable byte[] pdf,
            @Nullable String log,
            @Nullable String error,
            long elapsedMs) {

        public static Result success(byte[] pdf, @Nullable String log, long elapsedMs) {
            return new Result(true, pdf, log, null, elapsedMs);
        }

        public static Result failure(@Nullable String error, @Nullable String log, long elapsedMs) {
            return new Result(false, null, log, error, elapsedMs);
        }
    }
}
