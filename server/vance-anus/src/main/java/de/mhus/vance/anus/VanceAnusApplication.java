package de.mhus.vance.anus;

import de.mhus.vance.anus.access.AccessProperties;
import de.mhus.vance.anus.brain.AnusBrainProperties;
import de.mhus.vance.anus.compose.DockerComposeSetupBootstrap;
import de.mhus.vance.anus.compose.DockerComposeSetupWizard;
import de.mhus.vance.anus.devmode.DevModeProperties;
import de.mhus.vance.anus.setup.SetupBootstrap;
import de.mhus.vance.anus.sudo.SudoBootstrap;
import de.mhus.vance.shared.workspace.WorkspaceProperties;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration;
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
// Exclude Spring Boot's default Redis auto-configuration entirely — same
// reasoning as in VanceBrainApplication. All Redis is owned by VanceRedisConfig
// (vance* beans, gated on vance.redis.enabled); the Boot defaults contribute
// `redisMessageListenerContainer` + `stringRedisTemplate`, which make the
// ObjectProvider lookups in VanceRedisMessagingService ambiguous as soon as
// vance.redis.enabled=true (Anus fails to start), and open a localhost:6379
// connection even when Redis is disabled.
@SpringBootApplication(
        scanBasePackages = {"de.mhus.vance.anus", "de.mhus.vance.shared"},
        exclude = {
                DataRedisAutoConfiguration.class,
                DataRedisReactiveAutoConfiguration.class,
                DataRedisRepositoriesAutoConfiguration.class})
@EnableMongoRepositories(basePackages = {"de.mhus.vance.shared"})
@EnableMongoAuditing
// vance-shared declares WorkspaceProperties as the only @ConfigurationProperties
// bean — Brain enables it explicitly, Anus has to do the same so the
// WorkspaceService picked up by component scan can be wired. AccessProperties
// is Anus's own; AnusExceptionResolver and AuthAspect rely on it being a bean.
@EnableConfigurationProperties({WorkspaceProperties.class, AccessProperties.class,
        AnusBrainProperties.class, DevModeProperties.class,
        de.mhus.vance.shared.audit.AuditServiceProperties.class,
        // Anus shares the migration engine (SchemaMigrationService is component-scanned
        // from vance-shared) but has no boot trigger — it never migrates on its own.
        de.mhus.vance.shared.schema.SchemaMigrationProperties.class})
@EnableAspectJAutoProxy
public class VanceAnusApplication {

    public static void main(String[] args) {
        // Strip --sudo and --setup flags before Spring Boot sees them —
        // otherwise Spring Shell's NonInteractiveShellRunner would try to
        // run them as shell commands. Each bootstrap stashes its result in
        // a static holder that its dedicated ShellRunner reads back inside
        // the context. SudoBootstrap parses first so --sudo arguments are
        // consumed before SetupBootstrap scans the leftover argv.
        String[] remaining;
        try {
            remaining = SudoBootstrap.parse(args);
            remaining = SetupBootstrap.parse(remaining);
            remaining = DockerComposeSetupBootstrap.parse(remaining);
        } catch (IllegalArgumentException e) {
            System.err.println("anus: " + e.getMessage());
            System.exit(2);
            return;
        }
        if (DockerComposeSetupBootstrap.isMode()) {
            // Pure offline file scaffolder — writes docker-compose.yml + .env
            // into the mounted volume and exits. Runs BEFORE Spring Boot so the
            // Mongo-dependent context never boots (no database exists yet at
            // this point in a fresh install).
            System.exit(DockerComposeSetupWizard.run());
            return;
        }
        SpringApplication app = new SpringApplication(VanceAnusApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setLogStartupInfo(false);
        if (SudoBootstrap.isSudoMode() || SetupBootstrap.isSetupMode()) {
            // One-shot modes: stdout belongs to the calling script / the
            // wizard prompts. The ASCII banner would clutter pipes, logs
            // and the wizard UI.
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
