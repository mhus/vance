package de.mhus.vance.shared.workspace;

import de.mhus.vance.exec.EphemeralHandler;
import de.mhus.vance.exec.TempHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the Spring-free RootDir handlers of {@code vance-exec} as beans, so the
 * {@link WorkspaceService} finds them by type like the handlers that still live
 * here.
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
}
