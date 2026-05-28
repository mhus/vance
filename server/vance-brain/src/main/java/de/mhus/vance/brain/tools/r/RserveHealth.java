package de.mhus.vance.brain.tools.r;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.rosuda.REngine.REXP;
import org.rosuda.REngine.Rserve.RConnection;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Boot-time presence check for the Rserve daemon. Logs warn (not
 * error) when Rserve isn't reachable so the brain bean graph still
 * comes up; the {@link RScriptTool} surfaces a clean tool-time error
 * if a caller invokes R without the daemon present.
 *
 * <p>Convention mirrors {@code NodeHandler.verifyNpmAvailable} —
 * non-fatal boot diagnostics, descriptive log message.
 */
@Component
@EnableConfigurationProperties(RserveProperties.class)
@Slf4j
public class RserveHealth {

    private final RserveProperties props;
    private volatile boolean reachable;
    private volatile String version;

    public RserveHealth(RserveProperties props) {
        this.props = props;
    }

    @PostConstruct
    void verifyOnBoot() {
        if (!props.isEnabled()) {
            log.info("RserveHealth: disabled via vance.rserve.enabled=false");
            reachable = false;
            return;
        }
        Status s = probe();
        reachable = s.ok();
        version = s.versionString();
        if (reachable) {
            log.info("RserveHealth: connected to Rserve at {}:{} — R {} ready",
                    props.getHost(), props.getPort(), version);
        } else {
            log.warn("RserveHealth: Rserve not reachable at {}:{} — "
                            + "r_script tool will fail until the daemon is "
                            + "started. macOS: R -e 'Rserve::Rserve()'. "
                            + "Container: the Dockerfile starts it. ({})",
                    props.getHost(), props.getPort(), s.errorMessage());
        }
    }

    /** Open a short-lived connection, ping for R version, close.
     *  Used both on boot and as a runtime health probe. */
    public Status probe() {
        try {
            RConnection c = new RConnection(props.getHost(), props.getPort());
            try {
                REXP v = c.eval("paste(R.version$major, R.version$minor, sep='.')");
                String rv = v == null ? "(unknown)" : v.asString();
                return new Status(true, rv, null);
            } finally {
                c.close();
            }
        } catch (Exception e) {
            return new Status(false, null, e.getMessage());
        }
    }

    public boolean isReachable() { return reachable; }
    public String version() { return version; }
    public RserveProperties properties() { return props; }

    public record Status(boolean ok, String versionString, String errorMessage) {}
}
