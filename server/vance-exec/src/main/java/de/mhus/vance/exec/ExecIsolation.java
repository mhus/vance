package de.mhus.vance.exec;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Pure helper for {@code work_exec_run} OS-isolation. Wraps a command in
 * the configured isolation tool (bubblewrap, sandbox-exec, container, …)
 * so it physically sees only its RootDir, instead of running straight
 * under {@code /bin/sh -c}.
 *
 * <p>The wrapper template is whitespace-separated argv with two
 * placeholders: {@code {workdir}} (the job's RootDir cwd) and {@code {cmd}}
 * (the command, kept as a single argv element). It is passed to
 * {@code ProcessBuilder} as an argv list — never re-interpreted by a shell.
 */
public final class ExecIsolation {

    private ExecIsolation() {}

    /** Usable only with {@code mode: custom} and a wrapper containing {@code {cmd}}. */
    public static boolean enabled(@Nullable String mode, @Nullable String wrapper) {
        return "custom".equals(mode) && wrapper != null && !wrapper.isBlank() && wrapper.contains("{cmd}");
    }

    /** The command under the platform shell — what runs when isolation is off. */
    public static List<String> shellArgv(String command) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return windows ? List.of("cmd.exe", "/c", command) : List.of("/bin/sh", "-c", command);
    }

    /**
     * Builds the wrapped argv. Each whitespace token has {@code {workdir}}
     * substituted; a token equal to {@code {cmd}} becomes the command as a
     * single argv element.
     */
    public static List<String> wrap(String wrapper, String workdir, String command) {
        List<String> argv = new ArrayList<>();
        for (String token : wrapper.trim().split("\\s+")) {
            if (token.equals("{cmd}")) {
                argv.add(command);
            } else {
                argv.add(token.replace("{workdir}", workdir).replace("{cmd}", command));
            }
        }
        return argv;
    }
}
