package de.mhus.vance.foot.remote;

import de.mhus.vance.foot.config.FootConfig;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The local authorization gate for remote control.
 *
 * <p>Attaching to a foot is, in practice, shell access to the machine it runs on
 * — it holds {@code client_exec_run} and {@code client_file_write}. Two separate
 * questions therefore get two separate switches:
 *
 * <ul>
 *   <li><b>Is this client visible at all?</b> {@code mode=off} means it never
 *       announces; nobody can list it, let alone attach.</li>
 *   <li><b>May a remote line actually run?</b> In {@code ask} mode output
 *       streams but input is refused until someone at the terminal types
 *       {@code /remote allow}. In {@code allow} mode input runs immediately —
 *       that is the setting for walking away from a long job, and it has to be
 *       chosen before walking away, because there is nobody left to ask.</li>
 * </ul>
 *
 * <p>Deliberately <b>not</b> a blocking prompt on first attach: the request
 * arrives on the WS dispatch thread, and the case the feature exists for is
 * precisely the one where no human is there to answer a modal menu. A printed
 * hint plus an explicit command is honest about that; a prompt that always
 * times out into a denial would not be.
 */
@Component
@Slf4j
public class RemoteControlGate {

    public static final String MODE_OFF = "off";
    public static final String MODE_ASK = "ask";
    public static final String MODE_ALLOW = "allow";

    private final AtomicReference<String> mode = new AtomicReference<>(MODE_ASK);

    /** Set by {@code /remote allow} — one approval per process, not per attach. */
    private final AtomicBoolean approved = new AtomicBoolean();

    public RemoteControlGate(FootConfig config) {
        this.mode.set(normalize(config.getRemote().getMode()));
    }

    /** Whether this client announces itself and streams output at all. */
    public boolean isEnabled() {
        return !MODE_OFF.equals(mode.get());
    }

    /** Whether a remote input line may be executed. */
    public boolean isInputAllowed() {
        return MODE_ALLOW.equals(mode.get()) || approved.get();
    }

    public String mode() {
        return mode.get();
    }

    public boolean isApproved() {
        return approved.get();
    }

    /**
     * Grants remote input for the rest of this process. Returns {@code false}
     * when the client is {@code off} — approving input on a client that does not
     * even announce would be a silent contradiction, so the caller reports it.
     */
    public boolean approve() {
        if (!isEnabled()) {
            return false;
        }
        approved.set(true);
        log.info("remote control: input approved locally");
        return true;
    }

    /** Revokes a previous {@link #approve()}. Output keeps streaming. */
    public void revoke() {
        approved.set(false);
        log.info("remote control: input approval revoked");
    }

    /**
     * Switches mode at runtime. Returns the normalized value actually applied;
     * an unknown value falls back to {@code ask} rather than to {@code allow} —
     * a typo must never widen access.
     */
    public String setMode(String requested) {
        String normalized = normalize(requested);
        mode.set(normalized);
        if (MODE_OFF.equals(normalized)) {
            approved.set(false);
        }
        log.info("remote control: mode set to {}", normalized);
        return normalized;
    }

    private static String normalize(String raw) {
        if (raw == null) return MODE_ASK;
        return switch (raw.trim().toLowerCase()) {
            case MODE_OFF, "false", "no" -> MODE_OFF;
            case MODE_ALLOW, "true", "yes", "on" -> MODE_ALLOW;
            default -> MODE_ASK;
        };
    }
}
