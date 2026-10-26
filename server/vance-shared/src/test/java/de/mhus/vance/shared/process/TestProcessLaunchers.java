package de.mhus.vance.shared.process;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.springframework.beans.factory.ObjectProvider;

/**
 * Launchers for unit tests of the shared starters. The production launcher (sealed
 * environment) lives in the brain; these tests are about argv and exit handling, so
 * a plain {@link ProcessBuilder} is enough.
 */
public final class TestProcessLaunchers {

    private TestProcessLaunchers() {}

    /** Starts {@code spec} as is, inheriting the test JVM's environment. */
    public static ProcessLauncher direct() {
        return spec -> {
            ProcessBuilder pb = new ProcessBuilder(spec.command()).redirectErrorStream(spec.redirectErrorStream());
            if (spec.workingDir() != null) {
                pb.directory(spec.workingDir().toFile());
            }
            pb.environment().putAll(spec.environment());
            return pb.start();
        };
    }

    /** An {@link ObjectProvider} handing out {@code launcher}. */
    @SuppressWarnings("unchecked")
    public static ObjectProvider<ProcessLauncher> provider(ProcessLauncher launcher) {
        ObjectProvider<ProcessLauncher> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(launcher);
        return provider;
    }
}
