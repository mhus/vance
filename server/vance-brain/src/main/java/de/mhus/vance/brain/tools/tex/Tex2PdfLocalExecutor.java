package de.mhus.vance.brain.tools.tex;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Local {@link Tex2PdfExecutor} — runs {@code latexmk} via
 * {@link ProcessBuilder} on the local machine.
 *
 * <p>Extracted from the former {@code TexService.runLatexmk()} method.
 * On macOS, the JVM inherits only the minimal GUI PATH which does not
 * include TeX Live locations, so we probe known installation paths and
 * augment the {@code PATH} environment accordingly.
 *
 * <p>Always registered as a Spring bean; selected at runtime when the
 * {@code tex.executor} setting resolves to {@code "local"} (the default).
 */
@Component
@Slf4j
public class Tex2PdfLocalExecutor implements Tex2PdfExecutor {

    private static final long PROCESS_TIMEOUT_SECONDS = 150;
    private static final int LOG_EXCERPT_LINES = 50;

    @Override
    public String type() {
        return "local";
    }

    @Override
    public Result compile(Request request) {
        long start = System.currentTimeMillis();
        Path rootDir = request.workspaceRoot();
        String main = request.mainDocument();

        String engineFlag = switch (request.effectiveEngine()) {
            case "xelatex" -> "-pdfxe";
            case "lualatex" -> "-pdflua";
            default -> "-pdf";
        };

        String latexmkBin = resolveLatexmkBin();
        List<String> cmd = List.of(
                latexmkBin,
                engineFlag,
                "-interaction=nonstopmode",
                "-halt-on-error",
                main);

        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(rootDir.toFile())
                .redirectErrorStream(true);
        augmentPath(pb);
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            long elapsed = System.currentTimeMillis() - start;
            return Result.failure(
                    "Cannot start latexmk — is TeX Live installed? " + e.getMessage(),
                    null, elapsed);
        }

        String output;
        try {
            boolean finished = p.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                long elapsed = System.currentTimeMillis() - start;
                return Result.failure(
                        "TIMEOUT: latexmk did not finish within " + PROCESS_TIMEOUT_SECONDS + "s",
                        null, elapsed);
            }
            output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            long elapsed = System.currentTimeMillis() - start;
            return Result.failure("Interrupted", null, elapsed);
        } catch (IOException e) {
            p.destroyForcibly();
            long elapsed = System.currentTimeMillis() - start;
            return Result.failure("IO error reading latexmk output: " + e.getMessage(), null, elapsed);
        }

        long elapsed = System.currentTimeMillis() - start;
        int exit = p.exitValue();
        if (exit == 0) {
            // Read the PDF
            String pdfName = main.replaceAll("\\.tex$", ".pdf");
            Path pdfPath = rootDir.resolve(pdfName);
            if (!Files.exists(pdfPath)) {
                String logExcerpt = readLogExcerpt(rootDir, main);
                return Result.failure(
                        "latexmk exited 0 but output PDF not found: " + pdfName,
                        logExcerpt, elapsed);
            }
            try {
                byte[] pdfBytes = Files.readAllBytes(pdfPath);
                String logExcerpt = readLogExcerpt(rootDir, main);
                return Result.success(pdfBytes, logExcerpt, elapsed);
            } catch (IOException e) {
                return Result.failure(
                        "Could not read output PDF: " + e.getMessage(),
                        null, elapsed);
            }
        }

        // Non-zero exit — read .log file for error details
        String logExcerpt = readLogExcerpt(rootDir, main);
        String error = logExcerpt != null ? logExcerpt : truncate(output, 2000);
        return Result.failure(error, logExcerpt, elapsed);
    }

    // ──────────────────── latexmk binary resolution ────────────────────

    /**
     * Resolves the absolute path to {@code latexmk}. On macOS, the JVM
     * inherits only the minimal GUI PATH which does not include TeX Live
     * locations. Instead of relying on PATH resolution, we check the known
     * locations directly and return the first match. Falls back to
     * "latexmk" (bare name) so that on Linux/Docker the normal PATH lookup
     * still works.
     */
    private String resolveLatexmkBin() {
        List<String> candidates = List.of(
                "/Library/TeX/texbin/latexmk",                                   // macOS — MacTeX
                "/usr/local/texlive/2024/bin/universal-darwin/latexmk",          // TeX Live 2024
                "/usr/local/texlive/2024/bin/x86_64-darwin/latexmk",
                "/usr/local/texlive/2023/bin/universal-darwin/latexmk",          // TeX Live 2023
                "/usr/local/texlive/2023/bin/x86_64-darwin/latexmk",
                "/usr/local/bin/latexmk",                                        // Homebrew
                "/opt/homebrew/bin/latexmk",                                     // Apple Silicon Homebrew
                "/usr/bin/latexmk");                                             // Linux
        for (String candidate : candidates) {
            if (Files.isExecutable(Path.of(candidate))) {
                log.debug("tex2pdf: found latexmk at {}", candidate);
                return candidate;
            }
        }
        log.debug("tex2pdf: latexmk not found in known locations, falling back to bare 'latexmk'");
        return "latexmk";
    }

    private void augmentPath(ProcessBuilder pb) {
        String path = pb.environment().get("PATH");
        if (path == null) path = "";
        List<String> extra = List.of(
                "/Library/TeX/texbin",           // macOS — MacTeX
                "/usr/local/texlive/2024/bin/universal-darwin",
                "/usr/local/texlive/2023/bin/universal-darwin",
                "/opt/homebrew/bin");             // Apple Silicon homebrew
        List<String> toAdd = new ArrayList<>();
        for (String dir : extra) {
            if (!path.contains(dir)) toAdd.add(dir);
        }
        if (!toAdd.isEmpty()) {
            String newPath = String.join(":", toAdd) + (path.isEmpty() ? "" : ":" + path);
            pb.environment().put("PATH", newPath);
            log.debug("tex2pdf: augmented PATH to {}", newPath);
        }
    }

    // ──────────────────── log reading ────────────────────

    private @Nullable String readLogExcerpt(Path rootDir, String mainDocument) {
        String logFileName = mainDocument.replaceAll("\\.tex$", ".log");
        Path logPath = rootDir.resolve(logFileName);
        if (!Files.exists(logPath)) return null;
        try {
            List<String> lines = Files.readAllLines(logPath, StandardCharsets.UTF_8);
            int from = Math.max(0, lines.size() - LOG_EXCERPT_LINES);
            return String.join("\n", lines.subList(from, lines.size()));
        } catch (IOException e) {
            log.debug("Could not read log file {}: {}", logPath, e.toString());
            return null;
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...(truncated)";
    }
}
