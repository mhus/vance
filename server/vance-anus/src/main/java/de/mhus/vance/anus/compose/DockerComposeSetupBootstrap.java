package de.mhus.vance.anus.compose;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

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
 * <p>Agent mode: {@code --config <path|->} supplies all parameters as YAML
 * ({@code -} = stdin, so the config never has to land on the filesystem) and
 * {@code --dry-run} renders the output without writing. With a config present
 * the wizard never opens a terminal — no JLine, no {@code /dev/tty}, so it runs
 * headless inside a {@code docker run -i} pipe. Spec:
 * {@code specification/setup-agent-mode.md}.
 *
 * <p>Static state mirrors {@code SetupBootstrap}/{@code SudoBootstrap} — Anus is a
 * single-process tool, so wiring this through Spring would only complicate the
 * boot order.
 */
public final class DockerComposeSetupBootstrap {

    public static final String FLAG = "--setup-docker-compose";
    public static final String CONFIG_FLAG = "--config";
    public static final String DRY_RUN_FLAG = "--dry-run";

    private static volatile boolean mode = false;
    private static volatile String configSource = null;
    private static volatile boolean dryRun = false;

    private DockerComposeSetupBootstrap() {}

    /**
     * Strips {@code --setup-docker-compose}, {@code --config <path|->} and
     * {@code --dry-run} from {@code args} and returns the remainder.
     * Idempotent — repeated mode flags still enable the mode exactly once;
     * repeated {@code --dry-run} flags are likewise folded into one.
     *
     * @throws IllegalArgumentException when {@code --config} is last (no
     *         value) or carries an empty value — a missing config is a usage
     *         error the caller must see, not a silent fall-back to prompts.
     */
    public static String[] parse(String[] args) {
        List<String> remaining = new ArrayList<>(args.length);
        // Mode-flag presence first, then consume the agent flags only when they
        // belong to THIS mode — otherwise a sibling bootstrap(s parse would steal
        // them before their owner runs (parse order: sudo → setup → compose).
        boolean found = false;
        for (String a : args) {
            if (FLAG.equals(a)) {
                found = true;
                break;
            }
        }
        boolean dry = false;
        String config = null;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (FLAG.equals(a)) {
                continue;
            } else if (found && CONFIG_FLAG.equals(a)) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("--config needs a value: a file path or '-' for stdin");
                }
                String value = args[++i];
                if (value.isBlank()) {
                    throw new IllegalArgumentException("--config needs a value: a file path or '-' for stdin");
                }
                config = value;
            } else if (found && DRY_RUN_FLAG.equals(a)) {
                dry = true;
            } else {
                remaining.add(a);
            }
        }
        if (dry && config == null) {
            throw new IllegalArgumentException(
                    "--dry-run needs --config — there is nothing to dry-run without a config");
        }
        mode = found;
        configSource = config;
        dryRun = dry;
        return remaining.toArray(new String[0]);
    }

    /** {@code true} iff {@code --setup-docker-compose} appeared in argv. */
    public static boolean isMode() {
        return mode;
    }

    /**
     * The {@code --config} value: a file path, or {@code "-"} for stdin.
     * {@code null} when absent — the wizard runs interactively then.
     */
    public static @Nullable String configSource() {
        return configSource;
    }

    /** {@code true} iff {@code --dry-run} appeared in argv (render, write nothing). */
    public static boolean isDryRun() {
        return dryRun;
    }

    /** Test hook — resets the static holder. */
    static void reset() {
        mode = false;
        configSource = null;
        dryRun = false;
    }
}
