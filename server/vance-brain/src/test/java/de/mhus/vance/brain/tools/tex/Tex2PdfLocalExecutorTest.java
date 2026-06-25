package de.mhus.vance.brain.tools.tex;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link Tex2PdfLocalExecutor}. Tests focus on the
 * failure paths (latexmk not installed, timeout, missing PDF) since
 * a real TeX Live installation is not available in CI.
 */
class Tex2PdfLocalExecutorTest {

    private final Tex2PdfLocalExecutor executor = new Tex2PdfLocalExecutor();

    @TempDir
    Path tempDir;

    @Test
    void compile_returnsFailure_whenLatexmkNotInstalled() {
        // Point workspace root to an empty temp dir — latexmk will not be found
        // (or will fail to start)
        Tex2PdfExecutor.Request req = new Tex2PdfExecutor.Request(
                "thesis.tex", "pdflatex", tempDir, "acme", "proj", "proc-1");

        Tex2PdfExecutor.Result result = executor.compile(req);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).isNotNull();
    }

    @Test
    void compile_returnsFailure_whenMainTexNotFound() {
        // Even if latexmk runs, the main file doesn't exist
        Tex2PdfExecutor.Request req = new Tex2PdfExecutor.Request(
                "nonexistent.tex", "pdflatex", tempDir, "acme", "proj", "proc-1");

        Tex2PdfExecutor.Result result = executor.compile(req);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).isNotNull();
    }

    @Test
    void type_returnsLocal() {
        assertThat(executor.type()).isEqualTo("local");
    }

    @Test
    void request_effectiveEngine_defaultsToPdflatex() {
        Tex2PdfExecutor.Request req = new Tex2PdfExecutor.Request(
                "main.tex", null, tempDir, "acme", "proj", null);
        assertThat(req.effectiveEngine()).isEqualTo("pdflatex");
    }

    @Test
    void request_effectiveEngine_returnsTrimmed() {
        Tex2PdfExecutor.Request req = new Tex2PdfExecutor.Request(
                "main.tex", " xelatex ", tempDir, "acme", "proj", null);
        assertThat(req.effectiveEngine()).isEqualTo("xelatex");
    }

    @Test
    void request_carriesContext() {
        Tex2PdfExecutor.Request req = new Tex2PdfExecutor.Request(
                "main.tex", "pdflatex", tempDir, "acme", "proj-1", "proc-1");
        assertThat(req.tenantId()).isEqualTo("acme");
        assertThat(req.projectId()).isEqualTo("proj-1");
        assertThat(req.processId()).isEqualTo("proc-1");
    }

    @Test
    void result_success_factory() {
        byte[] pdf = {1, 2, 3};
        var result = Tex2PdfExecutor.Result.success(pdf, "log", 100);
        assertThat(result.success()).isTrue();
        assertThat(result.pdf()).isEqualTo(pdf);
        assertThat(result.log()).isEqualTo("log");
        assertThat(result.elapsedMs()).isEqualTo(100);
        assertThat(result.error()).isNull();
    }

    @Test
    void result_failure_factory() {
        var result = Tex2PdfExecutor.Result.failure("error", "log", 50);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).isEqualTo("error");
        assertThat(result.log()).isEqualTo("log");
        assertThat(result.elapsedMs()).isEqualTo(50);
        assertThat(result.pdf()).isNull();
    }
}
