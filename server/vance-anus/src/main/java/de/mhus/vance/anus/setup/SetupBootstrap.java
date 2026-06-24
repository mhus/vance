package de.mhus.vance.anus.setup;

import java.util.ArrayList;
import java.util.List;

/**
 * Argv flag-stripper + static holder for the {@code --setup} one-shot mode.
 *
 * <p>Called from {@code VanceAnusApplication.main} before Spring Boot starts
 * so Spring Shell's {@code NonInteractiveShellRunner} doesn't try to execute
 * {@code --setup} as a shell command. The actual wizard is driven from
 * {@link SetupShellRunner} once the context is up.
 *
 * <p>Static state mirrors {@code SudoBootstrap} — Anus is a single-process
 * tool, so wiring this through Spring would only complicate the boot order.
 */
public final class SetupBootstrap {

    public static final String FLAG = "--setup";

    private static volatile boolean setupMode = false;

    private SetupBootstrap() {}

    /**
     * Strips every occurrence of {@code --setup} from {@code args} and returns
     * the remainder for forwarding to Spring Boot. Idempotent — repeated flags
     * still enable setup mode exactly once.
     */
    public static String[] parse(String[] args) {
        List<String> remaining = new ArrayList<>(args.length);
        boolean found = false;
        for (String a : args) {
            if (FLAG.equals(a)) {
                found = true;
            } else {
                remaining.add(a);
            }
        }
        setupMode = found;
        return remaining.toArray(new String[0]);
    }

    /** {@code true} iff {@code --setup} appeared in argv. */
    public static boolean isSetupMode() {
        return setupMode;
    }

    /** Test hook — resets the static holder. */
    static void reset() {
        setupMode = false;
    }
}
