package de.mhus.vance.exec;

import java.util.function.Supplier;

/**
 * Launchers for unit tests of the starters. The production launcher (sealed
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

    /** A supplier handing out {@code launcher}. */
    public static Supplier<ProcessLauncher> provider(ProcessLauncher launcher) {
        return () -> launcher;
    }
}
