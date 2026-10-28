package de.mhus.vance.runner;

import de.mhus.vance.exec.GitOperationException;
import de.mhus.vance.exec.GitOperations;
import de.mhus.vance.exec.ProcessLauncher;
import de.mhus.vance.exec.ProcessSpec;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * {@link GitOperations} on the {@code git} program — the runner's twin of the brain's
 * JGit implementation, and the one that builds as a native image.
 *
 * <p>Credentials are per call: {@link #withCredentials} binds a token to the current
 * thread while a RootDir handler runs its git operations. The token goes to {@code git}
 * as an {@code http.<host>/.extraHeader} configured through the child's environment
 * ({@code GIT_CONFIG_*}) — scoped to the repository's host, never in argv (visible to
 * every process of the pod) and never stored in {@code .git/config}.
 */
final class ProgramGitOperations implements GitOperations {

    private static final long CLONE_TIMEOUT_SECONDS = 600;
    private static final long LOCAL_TIMEOUT_SECONDS = 120;
    private static final int OUTPUT_TAIL_CHARS = 2_000;

    private static final ThreadLocal<@Nullable GitCredentials> CREDENTIALS = new ThreadLocal<>();

    private final Supplier<ProcessLauncher> launcher;

    ProgramGitOperations(Supplier<ProcessLauncher> launcher) {
        this.launcher = launcher;
    }

    /** Runs {@code action} with {@code credentials} (or none) bound to this thread. */
    static <T> T withCredentials(@Nullable GitCredentials credentials, Supplier<T> action) {
        CREDENTIALS.set(credentials);
        try {
            return action.get();
        } finally {
            CREDENTIALS.remove();
        }
    }

    @Override
    public void init(Path dir) {
        run(dir, List.of("init", "--initial-branch=main"), null, LOCAL_TIMEOUT_SECONDS, false);
    }

    @Override
    public void clone(String repoUrl, Path dir, String branch, @Nullable Integer depth, Scope scope) {
        List<String> args = new ArrayList<>(List.of("clone", "--branch", branch));
        if (depth != null) {
            args.addAll(List.of("--depth", String.valueOf(depth)));
        }
        // "--" ends the options: a URL is data, even when it starts with a dash.
        args.addAll(List.of("--", repoUrl, "."));
        run(dir, args, repoUrl, CLONE_TIMEOUT_SECONDS, true);
    }

    @Override
    public void checkout(Path repo, String ref) {
        run(repo, List.of("checkout", ref, "--"), null, LOCAL_TIMEOUT_SECONDS, false);
    }

    @Override
    public @Nullable String head(Path repo) {
        try {
            String out = run(repo, List.of("rev-parse", "HEAD"), null, LOCAL_TIMEOUT_SECONDS, false)
                    .strip();
            return out.isEmpty() ? null : out;
        } catch (GitOperationException e) {
            return null;
        }
    }

    @Override
    public boolean isClean(Path repo) {
        return run(repo, List.of("status", "--porcelain"), null, LOCAL_TIMEOUT_SECONDS, false)
                .isBlank();
    }

    @Override
    public void checkoutNewBranch(Path repo, String branch) {
        run(repo, List.of("checkout", "-b", branch), null, LOCAL_TIMEOUT_SECONDS, false);
    }

    @Override
    public void commitAll(Path repo, String message) {
        run(repo, List.of("add", "-A"), null, LOCAL_TIMEOUT_SECONDS, false);
        run(
                repo,
                List.of("-c", "user.name=Vance", "-c", "user.email=vance@localhost", "commit", "-m", message),
                null,
                LOCAL_TIMEOUT_SECONDS,
                false);
    }

    @Override
    public void push(Path repo, String remote, String branch, Scope scope) {
        String url = run(
                        repo,
                        List.of("config", "--get", "remote." + remote + ".url"),
                        null,
                        LOCAL_TIMEOUT_SECONDS,
                        false)
                .strip();
        run(repo, List.of("push", remote, branch), url, CLONE_TIMEOUT_SECONDS, true);
    }

    /**
     * Runs {@code git <args>} in {@code dir} and returns its output.
     *
     * @param remoteUrl the repository URL when the call talks to a remote — decides which host the
     *     bound credentials are offered to; {@code null} for local operations
     */
    private String run(Path dir, List<String> args, @Nullable String remoteUrl, long timeoutSeconds, boolean network) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(args);
        ProcessSpec spec = ProcessSpec.of(command, dir).withEnvironment(environment(remoteUrl));
        if (network) {
            spec = spec.withNetwork();
        }
        String subcommand = subcommand(args);
        Process process;
        try {
            process = launcher.get().start(spec);
        } catch (IOException e) {
            throw new GitOperationException("Cannot start git: " + e.getMessage(), e);
        }
        try {
            // Output is small (git is quiet when it is not a terminal) and merged with stderr.
            byte[] bytes = process.getInputStream().readAllBytes();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new GitOperationException("git " + subcommand + " timed out after " + timeoutSeconds + " s");
            }
            String output = new String(bytes, StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new GitOperationException(
                        "git " + subcommand + " failed (exit " + process.exitValue() + "): " + tail(output));
            }
            return output;
        } catch (IOException e) {
            throw new GitOperationException("git " + subcommand + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new GitOperationException("git " + subcommand + " was interrupted", e);
        }
    }

    /** The git subcommand in {@code args}, skipping {@code -c key=value} pairs placed before it. */
    private static String subcommand(List<String> args) {
        for (int i = 0; i < args.size(); i++) {
            if ("-c".equals(args.get(i))) {
                i++; // its value
            } else if (!args.get(i).startsWith("-")) {
                return args.get(i);
            }
        }
        return args.getFirst();
    }

    private static Map<String, String> environment(@Nullable String remoteUrl) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("GIT_TERMINAL_PROMPT", "0");
        GitCredentials credentials = CREDENTIALS.get();
        String hostPrefix = remoteUrl == null ? null : httpsPrefix(remoteUrl);
        if (credentials != null && hostPrefix != null) {
            String basic = Base64.getEncoder()
                    .encodeToString(
                            (credentials.username() + ":" + credentials.token()).getBytes(StandardCharsets.UTF_8));
            env.put("GIT_CONFIG_COUNT", "1");
            env.put("GIT_CONFIG_KEY_0", "http." + hostPrefix + ".extraHeader");
            env.put("GIT_CONFIG_VALUE_0", "Authorization: Basic " + basic);
        }
        return env;
    }

    /** {@code https://host/} for an https URL, {@code null} for anything else (ssh, file, plain http). */
    private static @Nullable String httpsPrefix(String url) {
        try {
            URI uri = new URI(url);
            if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null) {
                return "https://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "") + "/";
            }
        } catch (URISyntaxException e) {
            // not a URL git would send a header to
        }
        return null;
    }

    private static String tail(String output) {
        String trimmed = output.strip();
        return trimmed.length() <= OUTPUT_TAIL_CHARS
                ? trimmed
                : "…" + trimmed.substring(trimmed.length() - OUTPUT_TAIL_CHARS);
    }
}
