package de.mhus.vance.foot;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Spring Boot bootstrap for the Foot CLI client. Runs headless (no embedded
 * web server) — the {@code FootRunner} drives Picocli once the context is up.
 *
 * <h2>App-level argument shims</h2>
 *
 * Some arguments must take effect <strong>before</strong> Spring's logging
 * and config systems boot, so they are intercepted here, translated into
 * system properties, and stripped from the args that Picocli sees. Each
 * shim mirrors a Spring property:
 *
 * <ul>
 *   <li>{@code --config <path>} / {@code -c <path>} / {@code --config=<path>}
 *       → {@code spring.config.additional-location=file:<path>}. Multiple
 *       allowed; later wins on key collisions. {@code --config-only} reset
 *       not implemented — we always merge.</li>
 *   <li>{@code --log-file <path>} / {@code --log-file=<path>} →
 *       {@code logging.file.name=<path>}. Spring's default file appender
 *       writes there. Without the flag, {@code logback-spring.xml} drives
 *       output to {@code vance-foot.log} as before.</li>
 *   <li>{@code --rest-api} (no value) →
 *       {@code vance.debug.rest.enabled=true}. Activates the debug REST
 *       server bean (gated by {@code @ConditionalOnProperty}). Used by
 *       QA's headless drives.</li>
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan({"de.mhus.vance.foot.config", "de.mhus.vance.foot.transfer", "de.mhus.vance.foot.agent"})
public class VanceFootApplication {

    static void main(String[] args) {
        String[] effectiveArgs = applyArgShims(args);
        SpringApplication app = new SpringApplication(VanceFootApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setLogStartupInfo(false);
        System.exit(SpringApplication.exit(app.run(effectiveArgs)));
    }

    /**
     * Strips the early-bind flags ({@code --config}, {@code --log-file},
     * {@code --rest-api}) from {@code args}, translates each into the
     * matching system property, and returns the remaining args for
     * Picocli to parse. Order of multiple {@code --config} flags is
     * preserved (Spring's later-wins semantics).
     */
    private static String[] applyArgShims(String[] args) {
        List<String> out = new ArrayList<>(args.length);
        List<String> configPaths = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--config".equals(arg) || "-c".equals(arg)) {
                if (i + 1 >= args.length) {
                    System.err.println("Error: " + arg + " requires a path argument.");
                    System.exit(2);
                }
                configPaths.add(args[++i]);
                continue;
            }
            if (arg.startsWith("--config=")) {
                configPaths.add(arg.substring("--config=".length()));
                continue;
            }
            if ("--log-file".equals(arg)) {
                if (i + 1 >= args.length) {
                    System.err.println("Error: --log-file requires a path argument.");
                    System.exit(2);
                }
                System.setProperty("logging.file.name", args[++i]);
                continue;
            }
            if (arg.startsWith("--log-file=")) {
                System.setProperty("logging.file.name", arg.substring("--log-file=".length()));
                continue;
            }
            if ("--rest-api".equals(arg)) {
                System.setProperty("vance.debug.rest.enabled", "true");
                continue;
            }
            out.add(arg);
        }
        if (!configPaths.isEmpty()) {
            StringBuilder locations = new StringBuilder();
            for (int i = 0; i < configPaths.size(); i++) {
                if (i > 0) locations.append(',');
                locations.append("optional:file:").append(configPaths.get(i));
            }
            // Spring reads this on boot. Existing user-set property wins —
            // we extend it rather than overwrite so explicit overrides hold.
            String existing = System.getProperty("spring.config.additional-location");
            String merged = existing == null || existing.isBlank()
                    ? locations.toString()
                    : existing + "," + locations;
            System.setProperty("spring.config.additional-location", merged);
        }
        return out.toArray(new String[0]);
    }
}
