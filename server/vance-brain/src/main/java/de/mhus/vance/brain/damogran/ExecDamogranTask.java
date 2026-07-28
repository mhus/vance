package de.mhus.vance.brain.damogran;

import de.mhus.vance.brain.damogran.DamogranManifest.TaskSpec;
import de.mhus.vance.shared.workspace.WorkspaceService;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Built-in {@code exec} task: runs a shell {@code command} on the run's
 * {@link ComposeExec} backend. Bounded by a hard-kill deadline
 * ({@code deadlineSeconds}, alias {@code timeoutSeconds}, {@code 0} = no kill):
 * the run blocks until the command finishes or the watchdog kills it — a runaway
 * command is terminated and the task fails cleanly, never left orphaned. The
 * exec mechanism (WORK {@code ExecManager} vs. remote {@code exec_run}) lives in
 * the backend, so the plain task is target-agnostic.
 *
 * <p>When a {@code state:} store is active for {@code exec} (WORK only), the
 * command is wrapped in a generated bash script that sources the persisted env
 * ({@code cache.env}), prepends the {@code header}, appends the {@code footer},
 * and writes back the shell variables the body created or changed (env-delta
 * against a baseline snapshot). See {@code planning/damogran-state.md}.
 */
@Service
class ExecDamogranTask implements DamogranTask {

    private static final String WRAPPER_PATH = ".damogran/state-run-exec.sh";

    private final DamogranStateService stateService;
    private final WorkspaceService workspaceService;
    private final ComposeSecretResolver secretResolver;

    ExecDamogranTask(DamogranStateService stateService, WorkspaceService workspaceService,
                     ComposeSecretResolver secretResolver) {
        this.stateService = stateService;
        this.workspaceService = workspaceService;
        this.secretResolver = secretResolver;
    }

    @Override
    public String type() {
        return "exec";
    }

    @Override
    public DamogranTaskResult execute(DamogranContext ctx, TaskSpec spec) {
        Map<String, String> secretEnv = secretResolver.resolve(spec.secrets(), ctx);
        DamogranStateService.StateDir sd = stateService.resolve(ctx, "exec");
        if (sd == null) {
            return DamogranTaskSupport.runExecTask(ctx, spec, secretEnv);
        }
        String command = DamogranTaskSupport.requireString(spec, "command");
        String script = wrap(sd, command, sd.readHeader(), sd.readFooter(), secretEnv.keySet());
        workspaceService.write(ctx.tenantId(), ctx.projectId(), ctx.workspaceDirName(),
                WRAPPER_PATH, script);
        // cwd is the workspace root, so the wrapper path is workspace-relative.
        ComposeExec.Result result = DamogranTaskSupport.runWithEnv(
                ctx.requireExec("exec"), "bash " + WRAPPER_PATH, secretEnv,
                DamogranTaskSupport.execDeadlineSeconds(spec));
        return DamogranTaskSupport.toResult(
                result, command, DamogranTaskSupport.outputsFor(ctx, spec), secretEnv.values());
    }

    /**
     * Build the bash state wrapper. The variable baseline is snapshotted
     * <em>before</em> the cache is sourced, so restored variables are treated as
     * "new" and re-persisted (they carry forward); pre-existing shell variables
     * (PATH, HOME, …) stay in the baseline and are skipped. Internal wrapper vars
     * (prefix {@code _dgs_}) and readonly/function entries are excluded. The
     * body's exit code is preserved as the script's exit code, so state is saved
     * even on a failing body but the task status still reflects the command.
     */
    private static String wrap(DamogranStateService.StateDir sd, String command,
                               String header, String footer, Set<String> secretNames) {
        String cache = DamogranStateService.posixQuote(sd.cacheRel());
        // Deny-list: never persist an injected secret env var, even if the body
        // re-declares it (the process-env form is already excluded via the
        // baseline snapshot, but a `FOO=…` inside the body would slip through).
        // Names are validated identifiers at parse time, so they are safe inside
        // the case pattern. `:` is a bash no-op when there are no secrets.
        String denyClause = secretNames.isEmpty()
                ? ":"
                : "case \"$_dgs_v\" in " + String.join("|", secretNames) + ") continue ;; esac";
        return """
                # Damogran state wrapper (exec) — generated, do not edit.
                _dgs_cache=%s
                _dgs_base="|$(compgen -v | tr '\\n' '|')|"
                [ -f "$_dgs_cache" ] && . "$_dgs_cache"
                # ---- header ----
                %s
                # ---- body ----
                %s
                _dgs_rc=$?
                # ---- footer ----
                %s
                # ---- serialize (env delta) ----
                {
                  for _dgs_v in $(compgen -v); do
                    case "$_dgs_v" in _dgs_*) continue ;; esac
                    %s
                    case "$_dgs_base" in *"|$_dgs_v|"*) continue ;; esac
                    declare -p "$_dgs_v" 2>/dev/null
                  done
                } > "$_dgs_cache"
                exit $_dgs_rc
                """.formatted(cache, header, command, footer, denyClause);
    }
}
