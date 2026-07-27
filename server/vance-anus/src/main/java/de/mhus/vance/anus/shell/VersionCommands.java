package de.mhus.vance.anus.shell;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;

/**
 * Reports the anus build version. The values come from the Maven reactor via
 * resource filtering ({@code vance.build.*} in {@code application.yml}), so
 * {@code version} prints the real release instead of a hardcoded string.
 *
 * <p>No {@code @RequiresAuth} — the version must be readable before login.
 */
@ShellComponent
public class VersionCommands {

    private final String version;
    private final String time;

    public VersionCommands(
            @Value("${vance.build.version:dev}") String version,
            @Value("${vance.build.time:}") String time) {
        this.version = version;
        this.time = time;
    }

    @ShellMethod(key = "version", value = "Show the anus build version.")
    public String version() {
        String line = "vance-anus " + version;
        if (time != null && !time.isBlank()) {
            line += " (built " + time + ")";
        }
        return line;
    }
}
