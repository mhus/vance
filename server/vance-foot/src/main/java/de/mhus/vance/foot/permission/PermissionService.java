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
    private volatile boolean sandboxEnabled;
    private volatile PermissionPolicy policy;

    public PermissionService(PermissionConfigLoader loader) {
        this.loader = loader;
        reload();
        log.info("permission sandbox {} at startup", sandboxEnabled ? "ENABLED" : "DISABLED");
    }

    /** Reloads config + policy from disk (e.g. after an "always" write). */
    public final void reload() {
        PermissionConfig effective = loader.effectiveConfig();
        this.sandboxEnabled = !cliDisabled && Boolean.TRUE.equals(effective.getSandbox());
        this.policy = PermissionPolicy.compile(effective, PermissionConfigLoader.DEFAULT_PATH_DENY);
    }

    public boolean isSandboxEnabled() {
        return sandboxEnabled;
    }

    /** Permanently disables the sandbox for this run ({@code --no-sandbox}). */
    public void disableSandbox() {
        this.cliDisabled = true;
        this.sandboxEnabled = false;
    }

    public PermissionPolicy policy() {
        return policy;
    }
}
