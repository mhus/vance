package de.mhus.vance.brain.jaglan.protocols;

import java.time.Duration;

import de.mhus.vance.toolpack.jaglan.JaglanInstance;
import de.mhus.vance.toolpack.jaglan.JaglanInstanceConfig;
import de.mhus.vance.toolpack.jaglan.JaglanProtocol;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The {@code demo} protocol — a mount that computes its own content.
 *
 * <p>Configuration, all in {@code _vance/config/mounts/<name>.yaml}:
 *
 * <pre>
 * protocol: demo
 * metadataTtlSeconds: 60   # optional
 * </pre>
 *
 * <p>No {@code rootDir}, no {@code baseUrl}, no credential: there is nothing
 * behind it. See {@link DemoJaglanInstance} for what it serves.
 *
 * <h2>Why this ships in the product and not in a test source set</h2>
 * Because the thing it demonstrates cannot be reached by hand otherwise. A
 * parameterised view is produced by the reader's REST endpoint or by an agent,
 * and until the form kind exists there is no surface a person can use to make
 * one — while {@code local} serves files that have no parameters and
 * {@code ode} needs a foreign application running. A fixture that only exists
 * inside a test run cannot answer "does this work in my brain".
 *
 * <p><b>It needs no property gate</b>, unlike {@code local}. That protocol can
 * expose the pod's file system, so it is off unless an operator names the
 * permissible roots. This one exposes nothing: it reads no disk, opens no
 * socket and holds no state, so the worst a mount of it can do is occupy a
 * folder name. It appears only where somebody wrote {@code protocol: demo} in
 * a mount document, which is opt-in by construction.
 */
@Component
@Slf4j
public class DemoJaglanProtocol implements JaglanProtocol {

    public static final String ID = "demo";

    static final String EXTRA_TTL_SECONDS = "metadataTtlSeconds";

    /**
     * Short by default. Nothing here changes, so a long TTL would buy nothing
     * and would make the mount look stuck to somebody testing against it.
     */
    static final Duration DEFAULT_TTL = Duration.ofSeconds(60);

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Demo (computed content)";
    }

    @Override
    public JaglanInstance instantiate(JaglanInstanceConfig config) {
        Duration ttl = DEFAULT_TTL;
        Object raw = config.extras().get(EXTRA_TTL_SECONDS);
        if (raw != null) {
            try {
                ttl = Duration.ofSeconds(Long.parseLong(String.valueOf(raw).trim()));
            } catch (NumberFormatException e) {
                // Falls back rather than refusing: an unreadable TTL on a demo
                // mount is not worth making the mount disappear over, and the
                // log line says which value was ignored.
                log.warn("Jaglan demo mount '{}': {}='{}' is not a number, using {}",
                        config.mount(), EXTRA_TTL_SECONDS, raw, DEFAULT_TTL);
            }
        }
        return new DemoJaglanInstance(config.mount(), ttl);
    }
}
