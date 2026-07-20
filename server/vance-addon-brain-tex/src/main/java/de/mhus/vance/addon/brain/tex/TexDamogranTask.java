package de.mhus.vance.addon.brain.tex;

import de.mhus.vance.brain.damogran.DamogranContext;
import de.mhus.vance.brain.damogran.DamogranManifest.TaskSpec;
import de.mhus.vance.brain.damogran.DamogranMime;
import de.mhus.vance.brain.damogran.DamogranTask;
import de.mhus.vance.brain.damogran.DamogranTaskResult;
import de.mhus.vance.brain.damogran.OutputArtifact;
import de.mhus.vance.shared.workspace.WorkspaceService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Damogran task {@code tex-task}: compiles a LaTeX document that is already
 * present in the compose workspace (imported by the compose's {@code import}
 * step) to a PDF, writing it back into the workspace as a declared output.
 *
 * <p>This is the addon-provided counterpart to the standalone {@code tex2pdf}
 * tool: it reuses the same {@link Tex2PdfExecutor} strategy (resolved via
 * {@link TexService#resolveExecutor}) but skips {@code TexService}'s own
 * temp-workspace + file-transport — Damogran owns provisioning and import.
 *
 * <p>Params: {@code main} (required, the entry {@code .tex} file, workspace-
 * relative), {@code engine} (optional: pdflatex/xelatex/lualatex). The PDF
 * output path is the task's first declared output ({@code output:}), else
 * {@code <main-basename>.pdf}. WORK target only.
 */
@Service
public class TexDamogranTask implements DamogranTask {

    private final TexService texService;
    private final WorkspaceService workspaceService;

    public TexDamogranTask(TexService texService, WorkspaceService workspaceService) {
        this.texService = texService;
        this.workspaceService = workspaceService;
    }

    @Override
    public String type() {
        return "tex-task";
    }

    @Override
    public DamogranTaskResult execute(DamogranContext ctx, TaskSpec spec) {
        Path workspaceRoot = ctx.workspacePath();
        if (workspaceRoot == null) {
            return DamogranTaskResult.failure("tex-task requires a server workspace (WORK target)");
        }
        String main = str(spec, "main");
        if (main == null) {
            return DamogranTaskResult.failure("tex-task requires 'main' (the entry .tex file)");
        }
        String engine = str(spec, "engine");
        String outputPath = spec.declaredOutputs().isEmpty()
                ? defaultPdfName(main)
                : spec.declaredOutputs().get(0).path();

        Tex2PdfExecutor executor;
        try {
            executor = texService.resolveExecutor(ctx.tenantId(), ctx.projectId(), ctx.processId());
        } catch (RuntimeException e) {
            return DamogranTaskResult.failure("tex-task: " + e.getMessage());
        }

        Tex2PdfExecutor.Result result = executor.compile(new Tex2PdfExecutor.Request(
                main, engine == null ? "" : engine, workspaceRoot,
                ctx.tenantId(), ctx.projectId(), ctx.processId()));

        if (!result.success() || result.pdf() == null) {
            String error = result.error() != null ? result.error() : "LaTeX compilation failed";
            return DamogranTaskResult.failure(error, result.log());
        }

        try {
            Path target = workspaceService.resolve(
                    ctx.tenantId(), ctx.projectId(), ctx.workspaceDirName(), outputPath);
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.write(target, result.pdf());
        } catch (IOException e) {
            return DamogranTaskResult.failure("tex-task: writing PDF failed: " + e.getMessage(), result.log());
        }

        OutputArtifact pdf = new OutputArtifact(
                outputPath, DamogranMime.kindForPath(outputPath), DamogranMime.mimeForPath(outputPath), null);
        return DamogranTaskResult.success(List.of(pdf), result.log());
    }

    private static @Nullable String str(TaskSpec spec, String key) {
        Object raw = spec.params().get(key);
        if (raw == null) {
            return null;
        }
        String s = raw.toString().trim();
        return s.isBlank() ? null : s;
    }

    private static String defaultPdfName(String main) {
        int slash = main.lastIndexOf('/');
        String base = slash >= 0 ? main.substring(slash + 1) : main;
        return base.replaceAll("\\.tex$", "") + ".pdf";
    }
}
