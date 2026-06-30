package de.mhus.vance.foot.permission;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Runtime holder for the sandbox state: the compiled {@link PermissionPolicy}
 * plus the on/off switch. Loaded once at construction from
 * {@link PermissionConfigLoader#effectiveConfig()} (central + local
 * tightening), refreshable after an "always" write.
 *
 * <p>The on/off switch combines two sources: the effective config's
 * {@code sandbox} value and the {@code --no-sandbox} CLI flag. The CLI
 * flag is the operator's deliberate runtime escape and {@link
 * #disableSandbox() wins} over any file setting — including a local
 * {@code sandbox: true}; a project file cannot police the person running
 * the binary on their own machine.
 */
@Service
@Slf4j
public class PermissionService {

    private final PermissionConfigLoader loader;

    /** Set once by {@code --no-sandbox}; overrides any file setting for this run. */
    private volatile boolean cliDisabled = false;
    /**
     * Whether a human can answer permission prompts. False in headless runs
     * ({@code --no-ui} / daemon) — there the prompt must auto-deny instead of
     * blocking forever on input that will never come.
     */
    private volatile boolean interactive = true;
    private volatile boolean sandboxEnabled;
    private volatile PermissionPolicy policy;
    private volatile ExecIsolation isolation = ExecIsolation.DISABLED;

    public PermissionService(PermissionConfigLoader loader) {
        this.loader = loader;
        reload();
        log.info("permission sandbox {} at startup (exec isolation {})",
                sandboxEnabled ? "ENABLED" : "DISABLED",
                isolation.enabled() ? "ON" : "off");
    }

    /** Reloads config + policy from disk (e.g. after an "always" write). */
    public final void reload() {
        PermissionConfig effective = loader.effectiveConfig();
        this.sandboxEnabled = !cliDisabled && Boolean.TRUE.equals(effective.getSandbox());
        this.policy = PermissionPolicy.compile(effective, PermissionConfigLoader.DEFAULT_PATH_DENY);
        this.isolation = resolveIsolation(effective);
    }

    /**
     * Resolves the effective exec-isolation. Active only when the sandbox
     * is on and the config requests {@code mode: custom} with a usable
     * wrapper template ({@code {cmd}} placeholder present). A broken
     * template logs an error and disables isolation — the allow/deny gate
     * still applies, the command just runs unwrapped.
     */
    private ExecIsolation resolveIsolation(PermissionConfig effective) {
        if (!sandboxEnabled) {
            return ExecIsolation.DISABLED;
        }
        PermissionConfig.Exec exec = effective.getExec();
        if (exec == null || exec.getIsolation() == null) {
            return ExecIsolation.DISABLED;
        }
        PermissionConfig.Isolation iso = exec.getIsolation();
        if (!"custom".equals(iso.getMode())) {
            return ExecIsolation.DISABLED;
        }
        String wrapper = iso.getWrapper();
        if (wrapper == null || wrapper.isBlank() || !wrapper.contains("{cmd}")) {
            log.error("exec isolation mode=custom but wrapper is missing/invalid "
                    + "(needs a '{cmd}' placeholder) — isolation DISABLED, commands run unwrapped");
            return ExecIsolation.DISABLED;
        }
        String workdir = iso.getWorkdir() == null || iso.getWorkdir().isBlank()
                ? "." : iso.getWorkdir();
        String resolvedWorkdir = PermissionPaths.canonicalize(workdir).toString();
        return new ExecIsolation(true, resolvedWorkdir, wrapper);
    }

    /** Effective exec-isolation for {@code client_exec_run}. */
    public ExecIsolation isolation() {
        return isolation;
    }

    public boolean isSandboxEnabled() {
        return sandboxEnabled;
    }

    /** Permanently disables the sandbox for this run ({@code --no-sandbox}). */
    public void disableSandbox() {
        this.cliDisabled = true;
        this.sandboxEnabled = false;
    }

    /** True when a human can answer permission prompts (interactive REPL). */
    public boolean isInteractive() {
        return interactive;
    }

    /** Marks this run headless ({@code --no-ui} / daemon) — prompts auto-deny. */
    public void setInteractive(boolean interactive) {
        this.interactive = interactive;
    }

    public PermissionPolicy policy() {
        return policy;
    }
}
