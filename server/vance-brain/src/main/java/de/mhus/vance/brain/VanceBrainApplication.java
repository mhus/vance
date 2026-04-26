package de.mhus.vance.brain;

import de.mhus.vance.brain.events.StreamingProperties;
import de.mhus.vance.brain.init.InitSettingsProperties;
import de.mhus.vance.brain.tools.exec.ExecProperties;
import de.mhus.vance.brain.tools.workspace.WorkspaceProperties;
import de.mhus.vance.brain.zaphod.ZaphodProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the Vance Brain server.
 *
 * <p>Scans both {@code de.mhus.vance.brain} (this module) and
 * {@code de.mhus.vance.shared} (services, repositories, filters) so beans
 * declared across both modules are picked up. Same applies to the Mongo
 * repositories — they live under {@code vance-shared} and need an explicit
 * {@link EnableMongoRepositories} base-package because the default
 * auto-configuration only scans the application package.
 */
@SpringBootApplication(scanBasePackages = {"de.mhus.vance.brain", "de.mhus.vance.shared"})
@EnableMongoRepositories(basePackages = "de.mhus.vance.shared")
@EnableConfigurationProperties({
        WorkspaceProperties.class,
        ExecProperties.class,
        StreamingProperties.class,
        InitSettingsProperties.class,
        ZaphodProperties.class})
@EnableScheduling
public class VanceBrainApplication {

    static void main(String[] args) {
        SpringApplication.run(VanceBrainApplication.class, args);
    }
}
