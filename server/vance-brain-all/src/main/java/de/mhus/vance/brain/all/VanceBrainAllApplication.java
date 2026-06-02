package de.mhus.vance.brain.all;

import de.mhus.vance.brain.VanceBrainApplication;
import org.springframework.boot.SpringApplication;

/**
 * Dev-bundle entry point. Delegates straight to {@link VanceBrainApplication}
 * as the Spring Boot primary source — extending it would cause Spring to
 * register {@code @EnableMongoRepositories} et al. twice (once via the
 * subclass-as-primary-source, once via the parent being scanned through
 * {@code @ComponentScan("de.mhus.vance.brain")}). Composition keeps the
 * configuration single-rooted.
 *
 * <p>Addons (slideshow, ...) sit on the classpath via this module's pom
 * dependencies; Spring Boot discovers them through their
 * {@code META-INF/spring/.../AutoConfiguration.imports} files without
 * any code change here.
 */
public class VanceBrainAllApplication {

    public static void main(String[] args) {
        SpringApplication.run(VanceBrainApplication.class, args);
    }
}
