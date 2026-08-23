package de.mhus.vance.foot.remote;

import de.mhus.vance.foot.config.FootConfig;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * This foot's identity on the remote-control channel.
 *
 * <p>The whole channel routes by {@link #clientId}, so its lifetime decides how
 * a reconnect behaves. It is generated once per <b>process</b> — deliberately
 * not derived from the WebSocket session:
 *
 * <ul>
 *   <li>A reconnect (even onto a different brain pod) keeps the id, because the
 *       process is still the same one doing the work. Nothing has to be
 *       re-addressed; the new pod simply subscribes the same key.</li>
 *   <li>A restart gets a new id, which is honest — the working state is gone,
 *       so it really is a different client.</li>
 * </ul>
 *
 * <p>{@link #label} is display only. Identity is the id; two foots on the same
 * machine in different directories are two clients even though their labels are
 * similar.
 */
@Component
@Slf4j
public class RemoteClientIdentity {

    private final String clientId;
    private final String label;
    private final String host;
    private final String cwd;
    private final long pid;
    private final String version;

    public RemoteClientIdentity(FootConfig config) {
        this.clientId = "fc_" + UUID.randomUUID().toString().replace("-", "");
        this.host = resolveHost();
        this.cwd = resolveCwd();
        this.pid = ProcessHandle.current().pid();
        this.version = safe(config.getClient().getVersion());
        this.label = host + ":" + cwd + " (pid " + pid + ")";
        log.debug("remote-control identity: clientId={} label='{}'", clientId, label);
    }

    public String clientId() {
        return clientId;
    }

    public String label() {
        return label;
    }

    public String host() {
        return host;
    }

    public String cwd() {
        return cwd;
    }

    public long pid() {
        return pid;
    }

    public String version() {
        return version;
    }

    private static String resolveHost() {
        try {
            String name = InetAddress.getLocalHost().getHostName();
            if (name != null && !name.isBlank()) {
                // Strip the domain — "mba.fritz.box" reads worse than "mba" in
                // a phone-sized list, and the FQDN adds nothing here.
                int dot = name.indexOf('.');
                return dot > 0 ? name.substring(0, dot) : name;
            }
        } catch (Exception e) {
            log.trace("hostname lookup failed: {}", e.toString());
        }
        String env = System.getenv("HOSTNAME");
        return env == null || env.isBlank() ? "unknown-host" : env;
    }

    /** Working directory, with {@code $HOME} collapsed to {@code ~} for readability. */
    private static String resolveCwd() {
        String cwd = System.getProperty("user.dir", "");
        String home = System.getProperty("user.home", "");
        if (!home.isBlank() && cwd.startsWith(home)) {
            return "~" + cwd.substring(home.length());
        }
        return cwd.isBlank() ? Path.of(".").toAbsolutePath().toString() : cwd;
    }

    private static String safe(String s) {
        return s == null || s.isBlank() ? "unknown" : s;
    }
}
