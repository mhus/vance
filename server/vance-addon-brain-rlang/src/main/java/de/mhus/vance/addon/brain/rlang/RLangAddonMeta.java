package de.mhus.vance.addon.brain.rlang;

import de.mhus.vance.shared.addon.VanceAddon;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Insights-side metadata for the rlang addon. Delegates {@link #status()}
 * to {@link RserveDaemonManager} so the Addons tab surfaces things like
 * "Rserve will start on first call" / "last start failed: R is not on PATH"
 * without admins having to grep brain logs.
 */
@Component
public class RLangAddonMeta implements VanceAddon {

    private final RserveDaemonManager daemon;

    public RLangAddonMeta(RserveDaemonManager daemon) {
        this.daemon = daemon;
    }

    @Override public String id() { return "rlang"; }

    @Override public String displayName() { return "R Language"; }

    @Override public @Nullable String status() { return daemon.status(); }
}
