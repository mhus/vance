package de.mhus.vance.foot.command;

import de.mhus.vance.api.access.AccessTokenResponse;
import de.mhus.vance.foot.auth.FootAuthService;
import de.mhus.vance.foot.auth.GitignoreGuard;
import de.mhus.vance.foot.auth.LoginRequest;
import de.mhus.vance.foot.auth.LoginResult;
import de.mhus.vance.foot.auth.ProjectBinding;
import de.mhus.vance.foot.auth.ProjectBindingStore;
import de.mhus.vance.foot.auth.VancePaths;
import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.PendingLinePrompt;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * {@code /login [username [project [password]]]} — authenticate against the
 * brain, persist a renewable token to {@code .vancetope/access.yaml} plus a
 * {@code .vancetope/project.eddie.yaml} binding, keep the credential out of git, then
 * (re)connect and bootstrap the bound project.
 *
 * <p>Any field not given as an argument is resolved from the existing
 * binding / config; a value that is still missing (or the password, which is
 * never read from config) is prompted for. The whole flow runs on a worker
 * thread because prompt answers arrive on the REPL input thread — blocking it
 * would deadlock.
 */
@Component
@Slf4j
public class LoginSlashCommand implements SlashCommand {

    private static final long PROMPT_TIMEOUT_MS = 120_000L;

    private final FootAuthService auth;
    private final ProjectBindingStore bindingStore;
    private final VancePaths paths;
    private final PendingLinePrompt prompt;
    private final GitignoreGuard gitignore;
    private final ConnectionService connection;
    private final ChatTerminal terminal;
    private final FootConfig config;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "vance-foot-login");
        t.setDaemon(true);
        return t;
    });

    public LoginSlashCommand(FootAuthService auth,
                             ProjectBindingStore bindingStore,
                             VancePaths paths,
                             PendingLinePrompt prompt,
                             GitignoreGuard gitignore,
                             @Lazy ConnectionService connection,
                             ChatTerminal terminal,
                             FootConfig config) {
        this.auth = auth;
        this.bindingStore = bindingStore;
        this.paths = paths;
        this.prompt = prompt;
        this.gitignore = gitignore;
        this.connection = connection;
        this.terminal = terminal;
        this.config = config;
    }

    @Override
    public String name() {
        return "login";
    }

    @Override
    public String description() {
        return "Log in and store a renewable token in .vancetope/access.yaml "
                + "(usage: /login [username [project [password]]]).";
    }

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
    }

    @Override
    public void execute(List<String> args) {
        // Run off the REPL input thread — prompts are answered on it.
        worker.submit(() -> {
            try {
                runLogin(args);
            } catch (Exception e) {
                terminal.error("Login failed: " + e.getMessage());
                log.debug("login flow failed", e);
            }
        });
    }

    // Package-private so the interactive prompt-with-defaults flow can be
    // exercised directly by unit tests without going through the worker thread.
    void runLogin(List<String> args) throws Exception {
        Optional<ProjectBinding> existing = loadExistingBinding();
        Defaults def = computeDefaults(args, existing.orElse(null), config);

        // Every field is prompted with the resolved default (from the
        // existing binding or config) offered as a prefill — a stored
        // binding is no longer taken silently. The user sees exactly what
        // they're logging in as and can confirm each value with Enter or
        // change it. Fields supplied as explicit CLI arguments skip their
        // prompt so scripted logins stay non-interactive.
        String username;
        if (!args.isEmpty() && !args.get(0).isBlank()) {
            username = args.get(0).trim();
        } else {
            String defaultUser = def.username() != null
                    ? def.username() : config.getAuth().getUsername();
            username = requireField("Username", defaultUser, false);
            if (username == null) {
                return; // cancelled / timed out
            }
        }

        // Brain URL and tenant have no CLI args — always prompt with the
        // resolved default so a re-login against a different brain/tenant
        // is possible without editing project.yaml first.
        String url = requireField("Brain URL", def.httpBase(), false);
        if (url == null) {
            return;
        }
        String httpBase = url;
        String wsBase = deriveWsBase(url);

        String tenant = requireField("Tenant", def.tenant(), false);
        if (tenant == null) {
            return;
        }

        String project;
        if (args.size() >= 2 && !args.get(1).isBlank()) {
            project = args.get(1).trim();
        } else {
            project = optionalField("Project (blank for none)", def.project());
        }

        String password;
        if (args.size() >= 3 && args.get(2) != null && !args.get(2).isEmpty()) {
            password = args.get(2);
        } else {
            password = requireField("Password", null, true);
            if (password == null) {
                return;
            }
        }

        terminal.info("Logging in as " + username + " @ " + httpBase + " …");
        LoginResult result = auth.login(new LoginRequest(
                httpBase, wsBase, tenant, username, project, password));

        reportGitignore(result.dir());
        reportSuccess(result);

        reconnect();
    }

    private @Nullable String requireField(String label, @Nullable String def, boolean masked) {
        while (true) {
            String shown = (def != null && !def.isBlank())
                    ? label + " [" + (masked ? "****" : def) + "]"
                    : label;
            String answer = prompt.ask(shown + ": ", masked, PROMPT_TIMEOUT_MS);
            if (answer == null) {
                terminal.warn(label + " prompt cancelled — login aborted.");
                return null;
            }
            String value = masked ? answer : answer.trim();
            if (!value.isEmpty()) {
                return value;
            }
            if (def != null && !def.isBlank()) {
                return def;
            }
            terminal.warn(label + " is required.");
        }
    }

    private @Nullable String optionalField(String label, @Nullable String def) {
        String shown = (def != null && !def.isBlank()) ? label + " [" + def + "]" : label;
        String answer = prompt.ask(shown + ": ", false, PROMPT_TIMEOUT_MS);
        if (answer == null) {
            return def;
        }
        String value = answer.trim();
        if (value.isEmpty()) {
            return def; // may be null → no project
        }
        return value;
    }

    private Optional<ProjectBinding> loadExistingBinding() {
        Optional<ProjectBinding> target = bindingStore.load(paths.loginTargetDir());
        if (target.isPresent()) {
            return target;
        }
        return bindingStore.load(paths.activeDir());
    }

    private void reportGitignore(java.nio.file.Path dir) {
        GitignoreGuard.Result result = gitignore.ensureAccessIgnored(dir);
        switch (result.kind()) {
            case ADDED -> terminal.warn("Added '" + result.entry() + "' to " + result.gitignore()
                    + " — the access token must not be committed.");
            case ALREADY_IGNORED, NO_GIT -> { /* nothing to say */ }
        }
    }

    private void reportSuccess(LoginResult result) {
        AccessTokenResponse token = result.token();
        terminal.info("Logged in — credentials saved to "
                + result.dir().resolve(VancePaths.ACCESS_FILE));
        terminal.verbose("Access token expires at "
                + Instant.ofEpochMilli(token.getExpiresAtTimestamp()));
        if (result.binding().getProject() != null) {
            terminal.info("Bound to project '" + result.binding().getProject() + "'.");
        }
    }

    private void reconnect() {
        try {
            if (connection.isOpen()) {
                connection.disconnect("re-login");
            }
            connection.connect();
        } catch (Exception e) {
            terminal.error("Connect after login failed: " + e.getMessage());
        }
    }

    /**
     * Resolves login fields from arguments, an existing binding, and config.
     * Package-private and pure for unit testing; prompting for anything still
     * {@code null} happens in the caller.
     */
    static Defaults computeDefaults(List<String> args,
                                    @Nullable ProjectBinding existing,
                                    FootConfig config) {
        String argUser = args.size() >= 1 ? blankToNull(args.get(0)) : null;
        String argProject = args.size() >= 2 ? blankToNull(args.get(1)) : null;
        String argPassword = args.size() >= 3 ? args.get(2) : null;

        boolean hadBinding = existing != null && existing.getBrain() != null
                && isSet(existing.getBrain().getHttpBase());

        String httpBase = hadBinding
                ? existing.getBrain().getHttpBase()
                : config.getBrain().getHttpBase();
        String wsBase = (hadBinding && isSet(existing.getBrain().getWsBase()))
                ? existing.getBrain().getWsBase()
                : config.getBrain().getWsBase();

        String tenant = (existing != null && isSet(existing.getTenant()))
                ? existing.getTenant()
                : config.getAuth().getTenant();

        // Config username is NOT a silent default: a fresh login (no binding)
        // prompts for it (with the config value offered as a prefill by the
        // caller) so the user does not accidentally log in as the dev account.
        String username = firstSet(argUser,
                existing != null ? existing.getUsername() : null);

        // Project: explicit arg wins; else existing binding; else null (prompt).
        String project = firstSet(argProject, existing != null ? existing.getProject() : null);

        return new Defaults(httpBase, wsBase, tenant, username, project, argPassword, hadBinding);
    }

    /** Derives a WebSocket base URL from an HTTP base ({@code https→wss}, {@code http→ws}). */
    static String deriveWsBase(String httpBase) {
        String h = httpBase.trim();
        if (h.startsWith("https://")) {
            return "wss://" + h.substring("https://".length());
        }
        if (h.startsWith("http://")) {
            return "ws://" + h.substring("http://".length());
        }
        return h; // already ws/wss or scheme-less — leave as given
    }

    /** Resolved defaults; {@code null} fields are prompted for. */
    record Defaults(String httpBase,
                    String wsBase,
                    String tenant,
                    @Nullable String username,
                    @Nullable String project,
                    @Nullable String password,
                    boolean hadBinding) {
    }

    private static boolean isSet(@Nullable String v) {
        return v != null && !v.isBlank();
    }

    private static @Nullable String blankToNull(@Nullable String v) {
        return isSet(v) ? v.trim() : null;
    }

    private static @Nullable String firstSet(@Nullable String... values) {
        for (String v : values) {
            if (isSet(v)) {
                return v.trim();
            }
        }
        return null;
    }
}
