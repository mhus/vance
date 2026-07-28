package de.mhus.vance.shared.build;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Logs the build version once at startup for every app that component-scans
 * {@code de.mhus.vance.shared} (brain, anus). Values come from the Maven
 * reactor via resource filtering ({@code vance.build.*}).
 *
 * <p>Listens on {@link ApplicationStartedEvent} — published right after the
 * context refresh and <b>before</b> the {@code ApplicationRunner}s. That
 * matters for anus, whose Spring-Shell REPL runner blocks, so the later
 * {@code ApplicationReadyEvent} would only fire on exit.
 */
@Component
@Slf4j
public class BuildVersionLogger {

    private final String appName;
    private final String version;
    private final String time;

    public BuildVersionLogger(
            @Value("${spring.application.name:vance}") String appName,
            @Value("${vance.build.version:dev}") String version,
            @Value("${vance.build.time:}") String time) {
        this.appName = appName;
        this.version = version;
        this.time = time;
    }

    @EventListener(ApplicationStartedEvent.class)
    public void logVersion() {
        if (!time.isBlank()) {
            log.info("{} version {} (built {})", appName, version, time);
        } else {
            log.info("{} version {}", appName, version);
        }
    }
}
