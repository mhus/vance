package de.mhus.vance.brain;

import de.mhus.vance.brain.arthur.ArthurProperties;
import de.mhus.vance.brain.events.StreamingProperties;
import de.mhus.vance.brain.bootstrap.BootstrapProperties;
import de.mhus.vance.brain.bootstrap.InitSettingsProperties;
import de.mhus.vance.brain.marvin.MarvinProperties;
import de.mhus.vance.brain.tools.exec.ExecProperties;
import de.mhus.vance.brain.transfer.BrainTransferProperties;
import de.mhus.vance.brain.ford.FordProperties;
import de.mhus.vance.brain.workspace.access.WorkspaceAccessProperties;
import de.mhus.vance.shared.workspace.WorkspaceProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the Vance Brain server.
 *
 * <p>Scans both {@code de.mhus.vance.brain} (this module) and
 * {@code de.mhus.vance.shared} (services, repositories, filters) so beans
 * declared across both modules are picked up. Same applies to the Mongo
 * repositories — most live under {@code vance-shared}, but a few are
 * brain-only (e.g. the notification-delivery audit log), so the
 * {@link EnableMongoRepositories} basePackages list covers both.
 */
@SpringBootApplication(scanBasePackages = {"de.mhus.vance.brain", "de.mhus.vance.shared"})
@EnableMongoRepositories(basePackages = {"de.mhus.vance.shared", "de.mhus.vance.brain"})
// Drives @CreatedDate / @LastModifiedDate on Mongo documents (e.g.
// ChatMessageDocument.createdAt). Without this, fields stay null on
// insert and any sort that relies on them is undefined.
@EnableMongoAuditing
@EnableConfigurationProperties({
        WorkspaceProperties.class,
        ExecProperties.class,
        StreamingProperties.class,
        BootstrapProperties.class,
        InitSettingsProperties.class,
        FordProperties.class,
        ArthurProperties.class,
        MarvinProperties.class,
        BrainTransferProperties.class,
        WorkspaceAccessProperties.class})
@EnableScheduling
public class VanceBrainApplication {

    static void main(String[] args) {
        SpringApplication.run(VanceBrainApplication.class, args);
    }
}
