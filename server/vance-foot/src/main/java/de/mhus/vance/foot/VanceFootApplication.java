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
 * <h2>{@code --config <path>} / {@code -c <path>}</h2>
 * Convenience shim mirroring the {@code vance-cli} UX. The argument is
 * intercepted before Spring reads {@code args} and translated into
 * {@code spring.config.additional-location=file:<path>}, so the file is
 * merged on top of the classpath {@code application.yaml} defaults. Anything
 * the file omits stays at the default. Multiple {@code --config} flags are
 * supported; later wins on key collisions, matching Spring's location order.
 */
@SpringBootApplication
@ConfigurationPropertiesScan({"de.mhus.vance.foot.config", "de.mhus.vance.foot.transfer"})
public class VanceFootApplication {

    static void main(String[] args) {
        String[] effectiveArgs = applyConfigShim(args);
        SpringApplication app = new SpringApplication(VanceFootApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setLogStartupInfo(false);
        System.exit(SpringApplication.exit(app.run(effectiveArgs)));
    }

    /**
     * Strips {@code --config <path>} / {@code --config=<path>} / {@code -c <path>}
     * pairs from {@code args} and accumulates them into the
     * {@code spring.config.additional-location} system property as a
     * comma-separated list of {@code file:} URIs. Spring picks up that
     * property before reading {@code args}, so the YAML files are merged on
     * boot.
     */
    private static String[] applyConfigShim(String[] args) {
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
