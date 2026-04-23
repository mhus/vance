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
 * <p>The result is cached on first call; a pod's IP shouldn't change during its
 * lifetime.
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

    private String resolve() {
        String env = System.getenv("VANCE_POD_IP");
        if (!isBlank(env)) return env;

        env = System.getenv("POD_IP");
        if (!isBlank(env) && !"127.0.0.1".equals(env)) return env;

        if (!isBlank(applicationHost)) return applicationHost;

        String hostname = System.getenv("HOSTNAME");
        if (!isBlank(hostname)) {
            try {
                InetAddress addr = InetAddress.getByName(hostname);
                String ip = addr.getHostAddress();
                if (ip != null && !ip.startsWith("127.")) return ip;
            } catch (Exception e) {
                log.debug("Could not resolve HOSTNAME '{}': {}", hostname, e.getMessage());
            }
        }

        String detected = scanInterfaces();
        if (detected != null) return detected;

        log.warn("Could not detect a pod IP — falling back to 'localhost'");
        return "localhost";
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
