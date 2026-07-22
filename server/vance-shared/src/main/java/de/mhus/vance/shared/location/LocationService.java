package de.mhus.vance.shared.location;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Determines the network address this pod advertises to peers.
 *
 * <p>Resolution order (first non-empty wins):
 * <ol>
 *   <li>{@code VANCE_POD_IP} env var — set by a Kubernetes downward-API ref in prod</li>
 *   <li>{@code POD_IP} env var — the conventional k8s name</li>
 *   <li>{@code spring.application.host} property — explicit override in config</li>
 *   <li>{@code HOSTNAME} env var resolved via DNS</li>
 *   <li>First non-loopback IPv4 on an up, non-loopback network interface</li>
 *   <li>{@code "localhost"} as last-resort fallback</li>
 * </ol>
 *
 * <p>The result is cached on first call. An operator-provided address
 * ({@code VANCE_POD_IP} / {@code POD_IP} / {@code spring.application.host} /
 * {@code HOSTNAME}) is authoritative and cached for the process lifetime. An
 * <em>auto-detected</em> address (interface scan / {@code localhost} fallback)
 * can silently go stale when the host's network changes underneath a running
 * process — laptop sleep/resume, DHCP lease change — so it is re-validated via
 * {@link #refreshPodAddress()} and re-resolved if it is no longer bound to a
 * live local interface.
 *
 * <p>Ported and trimmed from the nimbus {@code LocationService}.
 */
@Service
@Slf4j
public class LocationService {

    @Value("${server.port:8080}")
    private int serverPort;

    @Value("${spring.application.host:}")
    private String applicationHost;

    private @Nullable String cachedIp;

    /**
     * {@code true} when {@link #cachedIp} came from auto-detection (interface
     * scan or {@code localhost} fallback) rather than an operator-provided
     * source. Only auto-detected addresses are eligible for re-validation.
     */
    private boolean autoDetected;

    /** Returns the pod's advertised IP (or hostname-like string). Never {@code null}. */
    public synchronized String getPodIp() {
        if (cachedIp == null) {
            cachedIp = resolve();
            log.info("Pod IP resolved to '{}'", cachedIp);
        }
        return cachedIp;
    }

    public int getServerPort() {
        return serverPort;
    }

    /** Convenience: {@code ip:port}. */
    public String getPodAddress() {
        return getPodIp() + ":" + serverPort;
    }

    /**
     * Re-validates an auto-detected pod address and re-resolves it if the
     * cached IP is no longer bound to a live local interface — i.e. the host's
     * network changed while the process kept running. Operator-provided
     * addresses are authoritative and never revalidated. Cheap no-op when the
     * cached IP is still valid.
     *
     * <p>Called from the cluster heartbeat so a changed address propagates into
     * the {@code brain_pods} registry within one heartbeat interval, instead of
     * the pod advertising a dead endpoint (and self-proxying to it, see
     * {@code WorkspaceController}) until the process restarts. Returns the
     * current — possibly refreshed — {@code host:port}.
     */
    public synchronized String refreshPodAddress() {
        if (cachedIp != null && autoDetected && !isBoundLocally(cachedIp)) {
            String previous = cachedIp;
            String reResolved = resolve();
            if (!reResolved.equals(previous)) {
                cachedIp = reResolved;
                log.warn("Pod IP changed from '{}' to '{}' (interface no longer bound) — "
                        + "re-advertising to the cluster registry", previous, reResolved);
            }
        }
        return getPodAddress();
    }

    private String resolve() {
        String env = System.getenv("VANCE_POD_IP");
        if (!isBlank(env)) { autoDetected = false; return env; }

        env = System.getenv("POD_IP");
        if (!isBlank(env) && !"127.0.0.1".equals(env)) { autoDetected = false; return env; }

        if (!isBlank(applicationHost)) { autoDetected = false; return applicationHost; }

        String hostname = System.getenv("HOSTNAME");
        if (!isBlank(hostname)) {
            try {
                InetAddress addr = InetAddress.getByName(hostname);
                String ip = addr.getHostAddress();
                if (ip != null && !ip.startsWith("127.")) { autoDetected = false; return ip; }
            } catch (Exception e) {
                log.debug("Could not resolve HOSTNAME '{}': {}", hostname, e.getMessage());
            }
        }

        String detected = scanInterfaces();
        if (detected != null) { autoDetected = true; return detected; }

        log.warn("Could not detect a pod IP — falling back to 'localhost'");
        autoDetected = true;
        return "localhost";
    }

    /** {@code true} if {@code ip} is currently bound to an up, non-loopback interface. */
    private boolean isBoundLocally(String ip) {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface nif = interfaces.nextElement();
                if (nif.isLoopback() || !nif.isUp()) continue;
                Enumeration<InetAddress> addresses = nif.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    if (ip.equals(addresses.nextElement().getHostAddress())) return true;
                }
            }
        } catch (Exception e) {
            // Can't verify (e.g. interface enumeration failed) — assume still
            // valid rather than thrash the address on a transient error.
            log.debug("Could not verify local binding of '{}': {}", ip, e.getMessage());
            return true;
        }
        return false;
    }

    private @Nullable String scanInterfaces() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface nif = interfaces.nextElement();
                if (nif.isLoopback() || !nif.isUp()) continue;
                Enumeration<InetAddress> addresses = nif.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()
                            || addr instanceof java.net.Inet6Address) {
                        continue;
                    }
                    return addr.getHostAddress();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to scan network interfaces for pod IP: {}", e.getMessage());
        }
        return null;
    }

    private static boolean isBlank(@Nullable String s) {
        return s == null || s.isBlank();
    }
}
