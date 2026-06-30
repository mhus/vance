package de.mhus.vance.foot.tools;

import de.mhus.vance.foot.permission.InteractivePermissionResolver;
import de.mhus.vance.foot.permission.PermissionDecision;
import de.mhus.vance.foot.permission.PermissionDomain;
import de.mhus.vance.foot.permission.PermissionPaths;
import de.mhus.vance.foot.permission.PermissionService;
import java.nio.file.Path;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Gatekeeper for incoming brain-initiated tool invocations. Every
 * {@code client-tool-invoke} routes through {@link #permit} before the
 * actual tool runs (see {@link ClientToolService#dispatch}).
 *
 * <p>The sandbox gates two tool families against the user's permission
 * policy ({@link PermissionService}):
 * <ul>
 *   <li>{@code client_file_*} — checked by their {@code path} parameter
 *       against the path rules (globs);</li>
 *   <li>{@code client_exec_run} — checked by its {@code command} string
 *       against the command rules (regex).</li>
 * </ul>
 * Every other tool (exec job inspection, {@code client_javascript},
 * pack tools) is out of scope and always permitted. When the sandbox is
 * off ({@code --no-sandbox} / config), everything is permitted.
 *
 * <p>Evaluation yields {@link PermissionDecision#ALLOW ALLOW} /
 * {@link PermissionDecision#DENY DENY} / {@link PermissionDecision#ASK ASK}.
 * Until the interactive prompt lands (milestone 4), {@code permit} treats
 * {@code ASK} as the headless fallback and denies — exactly the behaviour
 * that applies when no human can answer.
 */
@Service
@Slf4j
public class ClientSecurityService {

    private static final String EXEC_RUN = "client_exec_run";
    private static final String FILE_PREFIX = "client_file_";

    private final PermissionService permissions;
    private final InteractivePermissionResolver prompt;

    public ClientSecurityService(PermissionService permissions,
                                 InteractivePermissionResolver prompt) {
        this.permissions = permissions;
        this.prompt = prompt;
    }

    /**
     * Decides whether the brain may invoke {@code toolName}. {@code true}
     * runs the tool; {@code false} makes {@link ClientToolService} reply
     * with {@link #denyReason} and never call the tool. An {@code ASK}
     * verdict triggers the interactive REPL prompt (which denies when no
     * human can answer).
     */
    public boolean permit(String toolName, Map<String, Object> params) {
        PermissionDecision decision = evaluate(toolName, params);
        switch (decision) {
            case ALLOW -> {
                return true;
            }
            case DENY -> {
                log.info("client-tool '{}' denied by sandbox", toolName);
                return false;
            }
            case ASK -> {
                Scope scope = scopeOf(toolName);
                PermissionDomain domain = scope == Scope.COMMAND
                        ? PermissionDomain.COMMANDS
                        : PermissionDomain.PATHS;
                PermissionDecision resolved =
                        prompt.resolve(toolName, domain, ruleSubject(scope, params));
                boolean allowed = resolved == PermissionDecision.ALLOW;
                log.info("client-tool '{}' {} by user prompt", toolName,
                        allowed ? "allowed" : "denied");
                return allowed;
            }
        }
        return false;
    }

    /** The subject string the prompt shows and (on "always") stores as a rule. */
    private String ruleSubject(Scope scope, Map<String, Object> params) {
        if (scope == Scope.COMMAND) {
            String command = stringParam(params, "command");
            return command == null ? "" : command;
        }
        return PermissionPaths.canonicalize(pathSubject(params)).toString();
    }

    /** Pure verdict for {@code toolName} given {@code params}. */
    public PermissionDecision evaluate(String toolName, Map<String, Object> params) {
        if (!permissions.isSandboxEnabled()) {
            return PermissionDecision.ALLOW;
        }
        Scope scope = scopeOf(toolName);
        return switch (scope) {
            case NONE -> PermissionDecision.ALLOW;
            case PATH -> {
                Path canonical = PermissionPaths.canonicalize(pathSubject(params));
                yield permissions.policy().evaluatePath(canonical);
            }
            case COMMAND -> {
                String command = stringParam(params, "command");
                if (command == null || command.isBlank()) {
                    // Malformed exec request — nothing safe to match against.
                    yield PermissionDecision.DENY;
                }
                yield permissions.policy().evaluateCommand(command);
            }
        };
    }

    /** Human-readable reason for a denied invocation, for the brain reply. */
    public String denyReason(String toolName, Map<String, Object> params) {
        Scope scope = scopeOf(toolName);
        String subject = switch (scope) {
            case PATH -> "path '" + PermissionPaths.canonicalize(pathSubject(params)) + "'";
            case COMMAND -> {
                String command = stringParam(params, "command");
                yield command == null || command.isBlank()
                        ? "an empty command"
                        : "command '" + command + "'";
            }
            case NONE -> "this request";
        };
        PermissionDecision decision = evaluate(toolName, params);
        if (decision == PermissionDecision.DENY) {
            return "Sandbox: '" + toolName + "' on " + subject
                    + " is blocked by a deny rule.";
        }
        return "Sandbox: '" + toolName + "' on " + subject
                + " is not permitted (no matching allow rule, and no interactive approval available).";
    }

    private Scope scopeOf(String toolName) {
        if (EXEC_RUN.equals(toolName)) {
            return Scope.COMMAND;
        }
        if (toolName.startsWith(FILE_PREFIX)) {
            return Scope.PATH;
        }
        return Scope.NONE;
    }

    /**
     * Path subject for a {@code client_file_*} tool. File tools default to
     * the working directory when {@code path} is omitted, so a blank value
     * is checked against {@code "."} (the CWD) rather than waved through.
     */
    private String pathSubject(Map<String, Object> params) {
        String path = stringParam(params, "path");
        return path == null || path.isBlank() ? "." : path;
    }

    private static @org.jspecify.annotations.Nullable String stringParam(
            Map<String, Object> params, String key) {
        Object value = params.get(key);
        return value instanceof String s ? s : null;
    }

    private enum Scope {
        PATH,
        COMMAND,
        NONE
    }
}
