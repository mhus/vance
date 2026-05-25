package de.mhus.vance.anus;

import de.mhus.vance.anus.access.AccessProperties;
import de.mhus.vance.anus.brain.AnusBrainProperties;
import de.mhus.vance.anus.devmode.DevModeProperties;
import de.mhus.vance.shared.workspace.WorkspaceProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * Bootstraps the Anus admin shell.
 *
 * <p>Scans {@code de.mhus.vance.anus} (this module) plus
 * {@code de.mhus.vance.shared} so the {@code TenantService},
 * {@code ProjectService}, {@code UserService}, {@code TeamService} and
 * the supporting Mongo repositories get wired into the Shell context.
 * No web server, no AI stack, no scheduling — Anus is interactive
 * REPL-only.
 */
@SpringBootApplication(scanBasePackages = {"de.mhus.vance.anus", "de.mhus.vance.shared"})
@EnableMongoRepositories(basePackages = {"de.mhus.vance.shared"})
@EnableMongoAuditing
// vance-shared declares WorkspaceProperties as the only @ConfigurationProperties
// bean — Brain enables it explicitly, Anus has to do the same so the
// WorkspaceService picked up by component scan can be wired. AccessProperties
// is Anus's own; AnusExceptionResolver and AuthAspect rely on it being a bean.
@EnableConfigurationProperties({WorkspaceProperties.class, AccessProperties.class,
        AnusBrainProperties.class, DevModeProperties.class})
@EnableAspectJAutoProxy
public class VanceAnusApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(VanceAnusApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setLogStartupInfo(false);
        System.exit(SpringApplication.exit(app.run(args)));
    }
}
