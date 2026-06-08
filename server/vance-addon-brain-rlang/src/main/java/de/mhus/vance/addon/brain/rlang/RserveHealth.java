package de.mhus.vance.addon.brain.rlang;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.rosuda.REngine.REXP;
import org.rosuda.REngine.Rserve.RConnection;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Short-lived health probe for the Rserve daemon. Owns the
 * {@link RserveProperties} binding for the addon. Boot-time
 * reachability is no longer logged — daemon start happens lazily on
 * first tool call via {@link RserveDaemonManager}, so a "not
 * reachable" probe at boot would just be noise.
 */
@Component
@EnableConfigurationProperties(RserveProperties.class)
@Slf4j
public class RserveHealth {

    private final RserveProperties props;
    private volatile @Nullable String version;

    public RserveHealth(RserveProperties props) {
        this.props = props;
    }

    /** Open a short-lived connection, ping for R version, close. */
    public Status probe() {
        try (CloseableR c = open()) {
            REXP v = c.conn.eval("paste(R.version$major, R.version$minor, sep='.')");
            String rv = v == null ? "(unknown)" : v.asString();
            version = rv;
            return new Status(true, rv, null);
        } catch (Exception e) {
            return new Status(false, null, e.getMessage());
        }
    }

    /** Cheap reachability check (no eval). */
    public boolean isReachable() {
        try (CloseableR c = open()) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public @Nullable String version() { return version; }
    public RserveProperties properties() { return props; }

    private CloseableR open() throws Exception {
        return new CloseableR(new RConnection(props.getHost(), props.getPort()));
    }

    public record Status(boolean ok, @Nullable String versionString, @Nullable String errorMessage) {}

    /** Try-with-resources wrapper around RConnection (which has close() but no AutoCloseable). */
    private static final class CloseableR implements AutoCloseable {
        final RConnection conn;
        CloseableR(RConnection conn) { this.conn = conn; }
        @Override public void close() {
            try { conn.close(); } catch (Exception ignored) { /* best-effort */ }
        }
    }
}
