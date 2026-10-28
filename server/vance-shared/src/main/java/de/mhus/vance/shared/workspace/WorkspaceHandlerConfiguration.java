package de.mhus.vance.shared.workspace;

import de.mhus.vance.exec.EphemeralHandler;
import de.mhus.vance.exec.GitHandler;
import de.mhus.vance.exec.GitOperations;
import de.mhus.vance.exec.NodeHandler;
import de.mhus.vance.exec.ProcessLauncher;
import de.mhus.vance.exec.PythonHandler;
import de.mhus.vance.exec.TempHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the Spring-free RootDir handlers of {@code vance-exec} as beans, so the
 * {@link WorkspaceService} finds them by type and the brain's tools can inject
 * {@link NodeHandler}. Git runs on JGit ({@link JGitOperations}); npm and pip start
 * through the application's {@link ProcessLauncher}, which the admin shell does not
 * have — there a start fails with a clear message.
 */
@Configuration
public class WorkspaceHandlerConfiguration {

    @Bean
    public TempHandler tempHandler() {
        return new TempHandler();
    }

    @Bean
    public EphemeralHandler ephemeralHandler() {
        return new EphemeralHandler();
    }

    @Bean
    public GitHandler gitHandler(GitOperations git) {
        return new GitHandler(git);
    }

    /** {@code verifyNpmAvailable} logs once at boot if npm is not on the PATH. */
    @Bean(initMethod = "verifyNpmAvailable")
    public NodeHandler nodeHandler(
            GitHandler gitHandler,
            GitOperations git,
            WorkspaceProperties properties,
            ObjectProvider<ProcessLauncher> launcher) {
        return new NodeHandler(
                gitHandler,
                git,
                () -> new NodeHandler.Config(
                        properties.getNode().getNpmPath(),
                        properties.getNode().getRegistry(),
                        properties.getNode().getMaxBytes()),
                launcher::getIfAvailable);
    }

    @Bean
    public PythonHandler pythonHandler(
            GitHandler gitHandler, GitOperations git, ObjectProvider<ProcessLauncher> launcher) {
        return new PythonHandler(gitHandler, git, launcher::getIfAvailable);
    }
}
