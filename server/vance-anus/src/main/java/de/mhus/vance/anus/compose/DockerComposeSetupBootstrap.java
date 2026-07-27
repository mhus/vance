package de.mhus.vance.anus.compose;

import java.util.ArrayList;
import java.util.List;

/**
 * Argv flag-stripper + static holder for the {@code --setup-docker-compose}
 * one-shot mode.
 *
 * <p>Unlike {@code --setup} (which drives a live MongoDB), this mode is a pure
 * offline file scaffolder: it writes a {@code docker-compose.yml} + {@code .env}
 * into a host-mounted volume and exits, <b>before any stack exists</b>. It must
 * therefore run without a Spring context and without a MongoDB connection.
 *
 * <p>{@code VanceAnusApplication.main} calls {@link #parse(String[])} first, and
 * when {@link #isMode()} is {@code true} it runs {@link DockerComposeSetupWizard}
 * as a standalone routine and exits — {@code SpringApplication.run(...)} is never
 * reached, so the Mongo-dependent context never boots.
 *
 * <p>Static state mirrors {@code SetupBootstrap}/{@code SudoBootstrap} — Anus is a
 * single-process tool, so wiring this through Spring would only complicate the
 * boot order.
 */
public final class DockerComposeSetupBootstrap {

    public static final String FLAG = "--setup-docker-compose";

    private static volatile boolean mode = false;

    private DockerComposeSetupBootstrap() {}

    /**
     * Strips every occurrence of {@code --setup-docker-compose} from
     * {@code args} and returns the remainder. Idempotent — repeated flags still
     * enable the mode exactly once.
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
        mode = found;
        return remaining.toArray(new String[0]);
    }

    /** {@code true} iff {@code --setup-docker-compose} appeared in argv. */
    public static boolean isMode() {
        return mode;
    }

    /** Test hook — resets the static holder. */
    static void reset() {
        mode = false;
    }
}
