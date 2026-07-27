package de.mhus.vance.addon.brain.rlang;

import de.mhus.vance.brain.damogran.DamogranContext;
import de.mhus.vance.brain.damogran.DamogranManifest.OutputSpec;
import de.mhus.vance.brain.damogran.DamogranManifest.TaskSpec;
import de.mhus.vance.brain.damogran.DamogranMime;
import de.mhus.vance.brain.damogran.DamogranTask;
import de.mhus.vance.brain.damogran.DamogranTaskResult;
import de.mhus.vance.brain.damogran.OutputArtifact;
import de.mhus.vance.shared.workspace.WorkspaceService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Damogran task {@code r}: evaluates an R script against the compose workspace
 * on a running Rserve daemon and surfaces the files the run produced as
 * workspace outputs.
 *
 * <p>The addon-provided counterpart to the standalone {@code r_script} tool.
 * Both share {@link RExecutionService}; the split of responsibility mirrors the
 * tex-task ↔ tex2pdf-tool split: this task binds the eval to the Damogran
 * workspace + output model instead of the tool's temp-dir + document-import.
 *
 * <p>Three R-specific rules (see {@code planning/damogran-system.md}):
 * <ol>
 *   <li><b>WORK only.</b> R runs on the pod-singleton Rserve daemon, not through
 *       the {@code WorkTargetDispatcher} — there is no CLIENT/DAEMON routing.
 *       A CLIENT-target compose therefore can't carry an {@code r} task.</li>
 *   <li><b>Isolation</b> is daemon-fork-per-connection + {@code setwd(<RootDir>)}
 *       only; no per-workspace interpreter.</li>
 *   <li><b>No package provisioning</b> — packages come from the brain's R image.</li>
 * </ol>
 *
 * <p>Params: inline {@code code} (the R script), or {@code script} (a
 * workspace-relative {@code .R} file to read and run). Outputs = the manifest's
 * declared {@code output:} entries plus any <em>new</em> top-level files the run
 * wrote into the workspace (dedup by path). The captured stdout/value rides the
 * result {@code log} like a notebook cell.
 */
@Service
public class RDamogranTask implements DamogranTask {

    private final RExecutionService rExecutionService;
    private final WorkspaceService workspaceService;

    public RDamogranTask(RExecutionService rExecutionService, WorkspaceService workspaceService) {
        this.rExecutionService = rExecutionService;
        this.workspaceService = workspaceService;
    }

    @Override
    public String type() {
        return "r";
    }

    @Override
    public DamogranTaskResult execute(DamogranContext ctx, TaskSpec spec) {
        Path workspaceRoot = ctx.workspacePath();
        if (workspaceRoot == null) {
            return DamogranTaskResult.failure(
                    "r task requires a server workspace (WORK target) — R runs on the "
                            + "pod's Rserve daemon and has no CLIENT/DAEMON path");
        }

        String script;
        try {
            script = resolveScript(ctx, spec);
        } catch (IOException e) {
            return DamogranTaskResult.failure("r task: could not read script file: " + e.getMessage());
        }
        if (script == null) {
            return DamogranTaskResult.failure(
                    "r task requires inline 'code' or a 'script' file path");
        }

        RExecutionService.Result res = rExecutionService.evaluate(script, workspaceRoot);
        String log = res.text().isBlank() ? null : res.text();
        if (!res.ok()) {
            return DamogranTaskResult.failure(res.errorMessage(), log);
        }
        return DamogranTaskResult.success(collectOutputs(spec, res, workspaceRoot), log);
    }

    /** Inline {@code code} wins; otherwise read the workspace-relative {@code script} file. */
    private @Nullable String resolveScript(DamogranContext ctx, TaskSpec spec) throws IOException {
        String code = string(spec, "code");
        if (code != null) {
            return code;
        }
        String scriptPath = string(spec, "script");
        if (scriptPath == null) {
            return null;
        }
        Path file = workspaceService.resolve(
                ctx.tenantId(), ctx.projectId(), ctx.workspaceDirName(), scriptPath);
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /**
     * Merge the manifest's declared outputs with the files the run produced.
     * Declared entries come first (they can pin a kind/title); discovered files
     * not already declared are appended with kind/mime inferred from the name.
     */
    private static List<OutputArtifact> collectOutputs(
            TaskSpec spec, RExecutionService.Result res, Path workspaceRoot) {
        List<OutputArtifact> artifacts = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (OutputSpec out : spec.declaredOutputs()) {
            String kind = out.kind() != null ? out.kind() : DamogranMime.kindForPath(out.path());
            artifacts.add(new OutputArtifact(
                    out.path(), kind, DamogranMime.mimeForPath(out.path()), out.title()));
            seen.add(out.path());
        }
        for (Path file : res.newFiles()) {
            String rel = workspaceRoot.relativize(file).toString().replace('\\', '/');
            if (seen.add(rel)) {
                artifacts.add(new OutputArtifact(
                        rel, DamogranMime.kindForPath(rel), DamogranMime.mimeForPath(rel), null));
            }
        }
        return artifacts;
    }

    private static @Nullable String string(TaskSpec spec, String key) {
        Object raw = spec.params().get(key);
        if (raw == null) {
            return null;
        }
        String s = raw.toString().trim();
        return s.isBlank() ? null : s;
    }
}
