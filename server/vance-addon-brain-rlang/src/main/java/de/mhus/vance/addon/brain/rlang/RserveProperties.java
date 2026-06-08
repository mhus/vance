package de.mhus.vance.addon.brain.rlang;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection settings + daemon lifecycle for the Rserve daemon used
 * by {@link RScriptTool}.
 *
 * <p>The Brain Docker image ships R + the Rserve R package
 * (install.packages('Rserve')); the daemon itself is started lazily
 * by {@link RserveDaemonManager} on the first {@code r_script} tool
 * call. Local macOS dev works the same way as long as R is on the
 * PATH and Rserve was {@code install.packages()}'d once.
 *
 * <p>Set {@code enabled=false} to suppress the daemon manager
 * entirely (the tool will then surface a clean "not reachable" error
 * when called). Set {@code autostart=false} when an external Rserve
 * is already running and managed elsewhere — the tool will probe but
 * not spawn.
 */
@ConfigurationProperties(prefix = "vance.rserve")
public class RserveProperties {

    private boolean enabled = true;
    private String host = "127.0.0.1";
    private int port = 6311;

    /**
     * Whether the addon should spawn {@code R CMD Rserve} on first
     * tool use when no daemon answers at {@link #host}:{@link #port}.
     * Forced off when {@link #host} is not loopback (we can't start
     * a remote daemon).
     */
    private boolean autostart = true;

    /**
     * Hard timeout for the spawned daemon to start answering on
     * {@link #port}. Reached only on misconfiguration (R installed
     * but Rserve package missing, port collision, …).
     */
    private int startupTimeoutSec = 10;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public boolean isAutostart() { return autostart; }
    public void setAutostart(boolean autostart) { this.autostart = autostart; }

    public int getStartupTimeoutSec() { return startupTimeoutSec; }
    public void setStartupTimeoutSec(int startupTimeoutSec) { this.startupTimeoutSec = startupTimeoutSec; }
}
