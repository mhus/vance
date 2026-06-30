package de.mhus.vance.brain.tools.exec;

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
final class ExecIsolation {

    private ExecIsolation() {}

    /** Usable only with {@code mode: custom} and a wrapper containing {@code {cmd}}. */
    static boolean enabled(ExecProperties.@Nullable Isolation iso) {
        return iso != null
                && "custom".equals(iso.getMode())
                && iso.getWrapper() != null
                && !iso.getWrapper().isBlank()
                && iso.getWrapper().contains("{cmd}");
    }

    /**
     * Builds the wrapped argv. Each whitespace token has {@code {workdir}}
     * substituted; a token equal to {@code {cmd}} becomes the command as a
     * single argv element.
     */
    static List<String> wrap(String wrapper, String workdir, String command) {
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
