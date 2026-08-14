package de.mhus.vance.brain.magrathea;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * The name this pod claims Magrathea tasks under. Resolved once at
 * construction: the value ends up in {@code MagratheaTaskDocument.claimedBy},
 * and a claim that identified its holder differently on every call would
 * make the reclaim scanner's ownership reasoning meaningless.
 *
 * <p>Shared by every claiming path — the scheduled {@link MagratheaTaskClaimer}
 * and the immediate {@link MagratheaLocalDispatch} — so both appear in the
 * journal as the same holder.
 */
@Component
@ConditionalOnProperty(
        value = "vance.services.magrathea",
        havingValue = "true",
        matchIfMissing = false)
@Slf4j
public class MagratheaPodIdentity {

    private final String podId;

    public MagratheaPodIdentity() {
        this.podId = resolve();
        log.debug("Magrathea pod identity: {}", podId);
    }

    public String podId() {
        return podId;
    }

    private static String resolve() {
        String envPod = System.getenv("POD_NAME");
        if (StringUtils.hasText(envPod)) return envPod;
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException ex) {
            return "unknown-pod";
        }
    }
}
