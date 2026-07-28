package de.mhus.vance.anus;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;

/**
 * Reads the Maven-injected build stamp ({@code vance.build.version} /
 * {@code vance.build.time}) straight off the classpath {@code application.yml},
 * <b>without</b> a Spring context.
 *
 * <p>The {@code --setup-docker-compose} scaffolder runs before
 * {@code SpringApplication.run} (see {@code DockerComposeSetupBootstrap}), so it
 * cannot use the {@code @Value}-injected version the {@code version} shell
 * command relies on. This helper parses the two stamp lines directly. Values are
 * filled by resource filtering at build time; on an unfiltered classpath (IDE
 * run against raw sources) they stay {@code @…@} and are reported as {@code dev}.
 */
public final class BuildInfo {

    private static final String VERSION = read("version", "dev");
    private static final String TIME = read("time", "");

    private BuildInfo() {}

    /** Reactor version, e.g. {@code 4.0.6}; {@code dev} on an unfiltered build. */
    public static String version() {
        return VERSION;
    }

    /** Build timestamp (ISO-8601 UTC), or {@code ""} if unavailable. */
    public static String time() {
        return TIME;
    }

    /** One-line stamp, e.g. {@code vance-anus 4.0.6 (built 2026-07-28T12:34:56Z)}. */
    public static String line() {
        String s = "vance-anus " + VERSION;
        if (!TIME.isBlank()) {
            s += " (built " + TIME + ")";
        }
        return s;
    }

    /**
     * Pulls {@code vance.build.<key>} out of {@code /application.yml}. Anchors on
     * the {@code build:} block so an unrelated {@code version:}/{@code time:} key
     * elsewhere in the file cannot shadow it. An unfiltered value (still bearing
     * the {@code @} delimiter) is treated as absent.
     */
    private static String read(String key, String fallback) {
        try (InputStream in = BuildInfo.class.getResourceAsStream("/application.yml")) {
            if (in == null) {
                return fallback;
            }
            String value = scan(in, key);
            if (value == null || value.isBlank() || value.contains("@")) {
                return fallback;
            }
            return value;
        } catch (IOException e) {
            return fallback;
        }
    }

    private static @Nullable String scan(InputStream in, String key) throws IOException {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            boolean inBuild = false;
            int buildIndent = -1;
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank() || line.stripLeading().startsWith("#")) {
                    continue;
                }
                int indent = indentOf(line);
                String trimmed = line.strip();
                if (!inBuild) {
                    if (trimmed.equals("build:")) {
                        inBuild = true;
                        buildIndent = indent;
                    }
                    continue;
                }
                if (indent <= buildIndent) {
                    return null; // left the build: block without a hit
                }
                if (trimmed.startsWith(key + ":")) {
                    return trimmed.substring(key.length() + 1).strip();
                }
            }
            return null;
        }
    }

    private static int indentOf(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i;
    }
}
