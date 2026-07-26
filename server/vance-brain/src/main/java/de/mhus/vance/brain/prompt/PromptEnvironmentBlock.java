package de.mhus.vance.brain.prompt;

import de.mhus.vance.api.ws.ClientContext;

/**
 * Renders a compact "Environment" prompt block describing the client at
 * the other end of a {@code CLIENT} work-target connection, so the LLM
 * generates shell commands for the platform its {@code client_exec_run} /
 * {@code client_file_*} calls actually run on instead of defaulting to
 * bash.
 *
 * <p>Rides as a
 * {@link de.mhus.vance.brain.ai.VanceSystemMessage#dynamic(String)
 * dynamic system message} — the client context is per-connection and
 * absent for headless turns, so it must stay behind the prompt-cache
 * marker like the sibling {@link PromptDateBlock}.
 *
 * <p>Blank output when there is nothing worth saying (no OS known); the
 * caller short-circuits the {@code dynamic} wrap.
 */
public final class PromptEnvironmentBlock {

    private PromptEnvironmentBlock() {}

    /**
     * Renders the block body for the given client context, or the empty
     * string when {@code context} carries no usable platform info.
     */
    public static String render(ClientContext context) {
        String os = context.getOs();
        if (os == null || os.isBlank()) {
            return "";
        }
        boolean windows = "windows".equalsIgnoreCase(os);
        String osLabel = switch (os.toLowerCase()) {
            case "windows" -> "Windows";
            case "macos" -> "macOS";
            case "linux" -> "Linux";
            default -> os;
        };

        StringBuilder b = new StringBuilder("## Environment\n");
        b.append("The connected client runs on ").append(osLabel);
        if (notBlank(context.getArch())) {
            b.append(" (").append(context.getArch()).append(")");
        }
        b.append(".");
        if (notBlank(context.getCwd())) {
            b.append(" Working directory: `").append(context.getCwd()).append("`.");
        }
        b.append("\n");

        String shell = context.getShell();
        if (shell == null || shell.isBlank()) {
            shell = windows ? "cmd.exe" : "/bin/sh";
        }
        b.append("`client_exec_run` executes commands through `").append(shell).append("` — ");
        if (windows) {
            b.append("generate Windows command syntax (cmd.exe builtins such as "
                    + "`dir`, `type`, `copy`, `del`, `&` for chaining, backslash paths), "
                    + "not POSIX/bash commands like `ls`/`cat`/`rm`.");
        } else {
            b.append("generate POSIX shell syntax.");
        }
        b.append("\n");

        b.append("Client file/exec sandbox: ");
        if (context.isSandboxEnabled()) {
            b.append("on — client-side file and exec calls are checked against the "
                    + "user's local permission policy and may be denied or require confirmation.");
        } else {
            b.append("off — client-side calls are not gated by a local policy.");
        }
        return b.toString();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
