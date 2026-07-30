package de.mhus.vance.anus.shell;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.stereotype.Component;

/**
 * Reports the anus build version. The values come from the Maven reactor via
 * resource filtering ({@code vance.build.*} in {@code application.yml}), so
 * {@code version} prints the real release instead of a hardcoded string.
 *
 * <p>No {@code @RequiresAuth} — the version must be readable before login.
 */
@Component
public class VersionCommands {

    private final String version;
    private final String time;

    public VersionCommands(
            @Value("${vance.build.version:dev}") String version,
            @Value("${vance.build.time:}") String time) {
        this.version = version;
        this.time = time;
    }

    @Command(name = "anusver", description = "Show the anus build version.")
    public String showVersion() {
        String line = "vance-anus " + version;
        if (time != null && !time.isBlank()) {
            line += " (built " + time + ")";
        }
        return line;
    }
}
