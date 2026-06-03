package de.mhus.vance.anus.sudo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.commons.lang3.StringUtils;

/**
 * Mini argv parser + static holder for the {@code --sudo} one-shot mode.
 *
 * <p>Called from {@code VanceAnusApplication.main} before Spring Boot starts.
 * Extracts every {@code --sudo <command>} pair from the raw argv into a
 * sequential command list and returns the remaining args (those go straight
 * to Spring Boot, where Spring Shell would otherwise pick them up as shell
 * commands and trip over our flag).
 *
 * <p>Static state is acceptable here because Anus is a single-process tool:
 * one JVM, one boot, then exit. Wiring this through Spring would only buy
 * us cycles in the boot order.
 *
 * <p>Recognised forms:
 * <ul>
 *   <li>{@code --sudo "tenant list"} — flag plus next arg
 *   <li>{@code --sudo=tenant list} — single arg with {@code =} separator
 * </ul>
 * Anything else (in particular a bare {@code --sudo} with no following arg)
 * raises {@link IllegalArgumentException}.
 */
public final class SudoBootstrap {

    public static final String FLAG = "--sudo";
    public static final String FLAG_EQ = "--sudo=";

    private static volatile List<String> commands = List.of();

    private SudoBootstrap() {}

    /**
     * Parses {@code args}, stashes any {@code --sudo} commands in the static
     * holder, and returns the remainder for forwarding to Spring Boot.
     */
    public static String[] parse(String[] args) {
        List<String> remaining = new ArrayList<>(args.length);
        List<String> sudoCommands = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (FLAG.equals(a)) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException(
                            "--sudo requires a command argument (e.g. --sudo \"tenant list\")");
                }
                String cmd = args[++i];
                if (StringUtils.isBlank(cmd)) {
                    throw new IllegalArgumentException("--sudo command must not be blank");
                }
                sudoCommands.add(cmd);
            } else if (a.startsWith(FLAG_EQ)) {
                String cmd = a.substring(FLAG_EQ.length());
                if (StringUtils.isBlank(cmd)) {
                    throw new IllegalArgumentException("--sudo command must not be blank");
                }
                sudoCommands.add(cmd);
            } else {
                remaining.add(a);
            }
        }
        commands = Collections.unmodifiableList(sudoCommands);
        return remaining.toArray(new String[0]);
    }

    /** {@code true} iff at least one {@code --sudo} command was extracted. */
    public static boolean isSudoMode() {
        return !commands.isEmpty();
    }

    /** The parsed {@code --sudo} command lines, in invocation order. Immutable. */
    public static List<String> commands() {
        return commands;
    }

    /** Test hook — resets the static holder. */
    static void reset() {
        commands = List.of();
    }
}
