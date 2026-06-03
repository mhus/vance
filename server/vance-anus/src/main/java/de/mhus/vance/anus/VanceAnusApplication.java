package de.mhus.vance.anus;

import de.mhus.vance.anus.access.AccessProperties;
import de.mhus.vance.anus.brain.AnusBrainProperties;
import de.mhus.vance.anus.devmode.DevModeProperties;
import de.mhus.vance.anus.sudo.SudoBootstrap;
import de.mhus.vance.shared.workspace.WorkspaceProperties;
import org.springframework.boot.Banner;
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
        AnusBrainProperties.class, DevModeProperties.class,
        de.mhus.vance.shared.audit.AuditServiceProperties.class})
@EnableAspectJAutoProxy
public class VanceAnusApplication {

    public static void main(String[] args) {
        // Strip --sudo flags before Spring Boot sees them — otherwise Spring
        // Shell's NonInteractiveShellRunner would try to run "--sudo" as a
        // shell command. SudoBootstrap stashes the parsed commands in a
        // static holder that SudoShellRunner reads back inside the context.
        String[] remaining;
        try {
            remaining = SudoBootstrap.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("anus: " + e.getMessage());
            System.exit(2);
            return;
        }
        SpringApplication app = new SpringApplication(VanceAnusApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setLogStartupInfo(false);
        if (SudoBootstrap.isSudoMode()) {
            // One-shot mode: stdout belongs to the calling script. The
            // ASCII banner would clutter pipes and logs for no benefit.
            app.setBannerMode(Banner.Mode.OFF);
        }
        try {
            System.exit(SpringApplication.exit(app.run(remaining)));
        } catch (RuntimeException e) {
            // In --sudo mode a failing command bubbles up here as the
            // Spring-Shell runner wraps it via SpringApplication's
            // ThrowingConsumer. The stack trace is already on stderr; we
            // just need to exit non-zero so the calling script notices.
            // Interactive mode never reaches this branch — the JLine REPL
            // catches per-line errors and stays in the loop.
            if (SudoBootstrap.isSudoMode()) {
                System.exit(1);
            }
            throw e;
        }
    }
}
