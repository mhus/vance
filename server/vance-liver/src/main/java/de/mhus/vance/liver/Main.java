package de.mhus.vance.liver;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Entry point for the {@code vancetope-backup} CLI — the Vance Liver
 * user-backup tool ({@code planning/user-backup.md}).
 *
 * <p>Picocli walks {@link Command @Command}-annotated classes for the
 * subcommand hierarchy: {@code vancetope-backup <subcommand> ...}. This
 * class is just the root; the actual subcommands are added in later
 * commits as they land ({@code init}, {@code run}, {@code status},
 * {@code list}, {@code verify}, {@code prune}, {@code find},
 * {@code history}, {@code extract}, {@code logout}).
 *
 * <p>Picocli exits the JVM itself on a successful parse — there is no
 * need for an explicit {@code System.exit} in the happy path. Failure
 * paths throw {@code CommandLine.ExecutionException} from a subcommand,
 * and Picocli's exit-code mapping (registered via {@link Command#exitCodeOnExecutionException})
 * translates that to the documented contract (planning §4.2: 0=ok,
 * 1=partial, 2=auth, 3=target, 4=config).
 *
 * <p>Logging goes through {@link System.Logger} (no SLF4J, no Logback —
 * the tool stays dependency-light so it can run on a stock JRE).
 */
@Command(
        name = "vancetope-backup",
        mixinStandardHelpOptions = true,
        version = "vancetope-backup 0.1.0-SNAPSHOT",
        description = "Local backup of all documents you can read on a Vance brain.",
        exitCodeOnExecutionException = 2,
        exitCodeOnInvalidInput = 4,
        subcommands = {
            // Subcommand classes land here as they are implemented.
            // Currently: info (the smallest end-to-end verification),
            // run (the actual backup). init, status, list, verify,
            // prune, find, history, extract, logout follow.
            de.mhus.vance.liver.cli.InfoCommand.class,
            de.mhus.vance.liver.cli.RunCommand.class,
        })
public final class Main implements Runnable {

    private static final System.Logger LOG = System.getLogger(Main.class.getName());

    /** System line separator (not loaded until needed — JVM-bound). */
    private static String nl() {
        return System.lineSeparator();
    }

    /** Singleton instance — Picocli sets fields on it and calls {@link #run()}. */
    public Main() {}

    @Override
    public void run() {
        // No subcommand matched → Picocli shows usage and exits with
        // USAGE by default. This branch is only reached when something
        // invokes Main directly without going through Picocli
        // dispatch (a programmer error). Log it; do not pollute stdout
        // because the operator already has the usage from Picocli.
        LOG.log(System.Logger.Level.WARNING, "vancetope-backup invoked without a subcommand");
    }

    /**
     * JVM entry point. Picocli parses {@code args}, dispatches to the
     * matching {@code @Command} subcommand, and calls {@code Runnable#run()}
     * on this instance when no subcommand matched (or on the matched
     * subcommand otherwise).
     */
    public static void main(String[] args) {
        // CommandLine returns the exit code; Picocli's default
        // IExecutionExceptionHandler would map an exception to the
        // configured exitCodeOnExecutionException, but for now we let
        // it bubble — subcommands will throw their own narrow
        // exceptions later.
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }

    /** Exposed for tests — the message body that lands on stdout when no subcommand is given. */
    static String usageBanner() {
        return "vancetope-backup — local backup tool for Vance." + nl()
                + nl()
                + "Usage: vancetope-backup <subcommand> [options]" + nl()
                + nl()
                + "Run with --help for the full subcommand list. A fresh"
                + nl()
                + "install starts with `vancetope-backup init` to create"
                + nl()
                + "the config file at ~/.vancetope/liver/backup.yaml."
                + nl();
    }
}
