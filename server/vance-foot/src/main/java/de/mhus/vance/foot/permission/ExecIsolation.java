package de.mhus.vance.foot.permission;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolved exec-isolation policy. When {@link #enabled}, a
 * {@code client_exec_run} command is wrapped in an OS-isolation tool
 * (bubblewrap, sandbox-exec, a container, …) instead of being handed
 * straight to {@code /bin/sh -c}.
 *
 * <p>{@link #wrapper} is a whitespace-separated argv template with two
 * placeholders:
 * <ul>
 *   <li>{@code {workdir}} — the single writable path (absolute);</li>
 *   <li>{@code {cmd}} — the original command string, kept as <b>one</b>
 *       argv element so the inner {@code sh -c {cmd}} parses it.</li>
 * </ul>
 * The template is tokenised on whitespace and passed to
 * {@code ProcessBuilder} as an argv list — it is never re-interpreted by
 * a shell, so the wrapper itself cannot be command-injected.
 */
public record ExecIsolation(boolean enabled, String workdir, String wrapper) {

    public static final ExecIsolation DISABLED = new ExecIsolation(false, "", "");

    /**
     * Builds the wrapped argv for {@code command}. Each whitespace token
     * of the wrapper template has its placeholders substituted; a token
     * equal to {@code {cmd}} becomes the command as a single argv element.
     *
     * @throws IllegalStateException if called while {@link #enabled} is false
     */
    public List<String> wrap(String command) {
        if (!enabled) {
            throw new IllegalStateException("exec isolation is disabled");
        }
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
