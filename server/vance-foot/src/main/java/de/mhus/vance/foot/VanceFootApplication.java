package de.mhus.vance.foot;

import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Spring Boot bootstrap for the Foot CLI client. Runs headless (no embedded
 * web server) — the {@code FootRunner} drives Picocli once the context is up.
 */
@SpringBootApplication
@ConfigurationPropertiesScan("de.mhus.vance.foot.config")
public class VanceFootApplication {

    static void main(String[] args) {
        SpringApplication app = new SpringApplication(VanceFootApplication.class);
        app.setBannerMode(Banner.Mode.OFF);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setLogStartupInfo(false);
        System.exit(SpringApplication.exit(app.run(args)));
    }
}
