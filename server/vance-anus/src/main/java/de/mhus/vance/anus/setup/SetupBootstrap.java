package de.mhus.vance.anus.setup;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Argv flag-stripper + static holder for the {@code --setup} one-shot mode.
 *
 * <p>Called from {@code VanceAnusApplication.main} before Spring Boot starts
 * so Spring Shell's {@code NonInteractiveShellRunner} doesn't try to execute
 * {@code --setup} as a shell command. The actual wizard is driven from
 * {@link SetupShellRunner} once the context is up.
 *
 * <p>Agent mode: {@code --config <path|->} supplies all parameters as YAML
 * ({@code -} = stdin, so the config never has to land on the filesystem — it
 * carries the user's password and the AI API key) and {@code --dry-run} prints
 * the plan without writing. With a config present the wizard never opens a
 * terminal. Spec: {@code specification/setup-agent-mode.md}.
 *
 * <p>Static state mirrors {@code SudoBootstrap} — Anus is a single-process
 * tool, so wiring this through Spring would only complicate the boot order.
 */
public final class SetupBootstrap {

    public static final String FLAG = "--setup";
    public static final String CONFIG_FLAG = "--config";
    public static final String DRY_RUN_FLAG = "--dry-run";

    private static volatile boolean setupMode = false;
    private static volatile String configSource = null;
    private static volatile boolean dryRun = false;

    private SetupBootstrap() {}

    /**
     * Strips every occurrence of {@code --setup} from {@code args} and returns
     * the remainder for forwarding to Spring Boot. Idempotent — repeated flags
     * still enable setup mode exactly once. Also strips the agent-mode
     * {@code --config <path|->} and {@code --dry-run} flags.
     *
     * @throws IllegalArgumentException when {@code --config} is last (no
     *         value), carries an empty value, or {@code --dry-run} appears
     *         without {@code --config} — a usage error the caller must see,
     *         not a silent fall-back to prompts.
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
        setupMode = found;
        configSource = config;
        dryRun = dry;
        return remaining.toArray(new String[0]);
    }

    /** {@code true} iff {@code --setup} appeared in argv. */
    public static boolean isSetupMode() {
        return setupMode;
    }

    /**
     * The {@code --config} value: a file path, or {@code "-"} for stdin.
     * {@code null} when absent — the wizard runs interactively then.
     */
    public static @Nullable String configSource() {
        return configSource;
    }

    /** {@code true} iff {@code --dry-run} appeared in argv (plan only, write nothing). */
    public static boolean isDryRun() {
        return dryRun;
    }

    /** Test hook — resets the static holder. */
    static void reset() {
        setupMode = false;
        configSource = null;
        dryRun = false;
    }
}
