package de.mhus.vance.foot.session;

import de.mhus.vance.api.profile.ProfileDto;
import de.mhus.vance.api.profile.ProfileSettingWriteRequest;
import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.connection.BrainRestClientService;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.ui.ChatTerminal;
import jakarta.annotation.PreDestroy;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * Seeds the account's {@code display.timezone} user setting from this
 * machine's zone on the first connect where none is configured — the
 * CLI equivalent of the Web-UI persisting the browser timezone when the
 * profile has none. Without this, headless turns (schedulers,
 * auto-wakeup) and the "current date" prompt block would fall back to
 * the tenant default or UTC for a CLI-only user who never opened the
 * web profile.
 *
 * <p><b>Only-if-absent:</b> an existing value (set via the web profile
 * or {@code /timezone}) is never overwritten — a user on a laptop in a
 * different zone keeps their chosen timezone. Explicit changes go
 * through {@link de.mhus.vance.foot.command.TimezoneSlashCommand}.
 *
 * <h2>Threading</h2>
 * The trigger arrives on the WebSocket-listener thread (via
 * {@code WelcomeHandler}); the REST calls run on a dedicated
 * single-thread executor so a slow/hung request can't stall the
 * listener. Failures are logged verbose and swallowed — a missed seed
 * must never break the session.
 */
@Service
public class TimezoneSeedService {

    /** Explicit opt-out — set by a future {@code --no-timezone-seed} flag if needed. */
    public static final String SKIP_PROPERTY = "vance.timezone.seed.skip";

    /**
     * Setting key. Literal on purpose — vance-foot depends on
     * {@code vance-api} only. Must stay in sync with
     * {@code TimezoneResolver.Keys.DISPLAY_TIMEZONE} in vance-shared.
     */
    private static final String TIMEZONE_KEY = "display.timezone";

    private final FootConfig config;
    private final ConnectionService connection;
    private final BrainRestClientService rest;
    private final ChatTerminal terminal;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "vance-foot-timezone-seed");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean inFlight = new AtomicBoolean(false);

    /**
     * @param connection {@code @Lazy} to break the cycle
     *   ConnectionService → MessageDispatcher → WelcomeHandler →
     *   TimezoneSeedService → ConnectionService.
     * @param rest {@code @Lazy} for the same reason — the REST client
     *   also depends on ConnectionService for the JWT.
     */
    public TimezoneSeedService(FootConfig config,
                               @Lazy ConnectionService connection,
                               @Lazy BrainRestClientService rest,
                               ChatTerminal terminal) {
        this.config = config;
        this.connection = connection;
        this.rest = rest;
        this.terminal = terminal;
    }

    /** Called by {@code WelcomeHandler}. Returns immediately; work runs on the executor. */
    public void triggerAfterWelcome() {
        if (Boolean.parseBoolean(System.getProperty(SKIP_PROPERTY, "false"))) {
            terminal.verbose("Timezone seed skipped (" + SKIP_PROPERTY + ").");
            return;
        }
        if (!inFlight.compareAndSet(false, true)) {
            terminal.verbose("Timezone seed already in flight — ignoring duplicate trigger.");
            return;
        }
        executor.submit(() -> {
            try {
                runSeed();
            } finally {
                inFlight.set(false);
            }
        });
    }

    private void runSeed() {
        if (!connection.isOpen()) {
            terminal.verbose("Timezone seed skipped — connection not open.");
            return;
        }
        try {
            @Nullable String current = currentTimezone();
            if (current != null && !current.isBlank()) {
                terminal.verbose("Timezone already set (" + current + ") — no seed needed.");
                return;
            }
            String zone = ZoneId.systemDefault().getId();
            rest.put(profilePath() + "/settings/" + TIMEZONE_KEY,
                    ProfileSettingWriteRequest.builder().value(zone).build(),
                    ProfileDto.class);
            terminal.verbose("Timezone seeded from this machine: " + zone + ".");
        } catch (Exception e) {
            terminal.verbose("Timezone seed failed (ignored): " + e.getMessage());
        }
    }

    private @Nullable String currentTimezone() throws Exception {
        ProfileDto profile = rest.get(profilePath(), ProfileDto.class);
        Map<String, String> settings = profile.getWebUiSettings();
        return settings == null ? null : settings.get(TIMEZONE_KEY);
    }

    private String profilePath() {
        return "/brain/" + config.getAuth().getTenant() + "/profile";
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
