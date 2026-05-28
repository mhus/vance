package de.mhus.vance.brain.tools.r;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection settings for the Rserve daemon used by {@link RScriptTool}.
 *
 * <p>Defaults assume the Rserve daemon is reachable on
 * {@code localhost:6311} — the upstream default. In a containerised
 * setup the daemon runs in the same pod as the brain (started by the
 * Dockerfile's entrypoint); on a laptop the user runs it via
 * {@code R -e 'Rserve::Rserve()'} once.
 *
 * <p>Setting {@code enabled=false} disables the {@code r_script}
 * tool's boot-check warning when the operator knows R is not part of
 * this deployment.
 */
@ConfigurationProperties(prefix = "vance.rserve")
public class RserveProperties {

    private boolean enabled = true;
    private String host = "127.0.0.1";
    private int port = 6311;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
}
