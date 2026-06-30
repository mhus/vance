package de.mhus.vance.foot.permission;

import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Immutable, compiled rule set. Built once from a {@link PermissionConfig}
 * (plus the bundled deny-floor) and queried per tool call. Pure matching:
 * the sandbox on/off switch and the interactive prompt live in the
 * service layer, not here.
 *
 * <p>Evaluation order per domain is always <b>deny → allow → ask</b>:
 * a matching deny rule wins outright (no prompt), a matching allow rule
 * permits, and no match means the user must be asked.
 *
 * <p>Paths are matched as globs against the {@link PermissionPaths#canonicalize
 * canonical} subject path; commands are matched as regex (via
 * {@link java.util.regex.Matcher#find}) against the raw command string,
 * so the rule author controls anchoring with {@code ^}/{@code $}.
 */
public final class PermissionPolicy {

    private final List<PathMatcher> pathDeny;
    private final List<PathMatcher> pathAllow;
    private final List<Pattern> commandDeny;
    private final List<Pattern> commandAllow;

    private PermissionPolicy(
            List<PathMatcher> pathDeny,
            List<PathMatcher> pathAllow,
            List<Pattern> commandDeny,
            List<Pattern> commandAllow) {
        this.pathDeny = List.copyOf(pathDeny);
        this.pathAllow = List.copyOf(pathAllow);
        this.commandDeny = List.copyOf(commandDeny);
        this.commandAllow = List.copyOf(commandAllow);
    }

    /**
     * Compiles a policy. {@code floorPathDeny} is merged into the path
     * deny list ahead of the file's own deny rules — these are the
     * non-overridable safety floor (e.g. {@code ~/.ssh/**}).
     *
     * @throws PermissionConfigException if a command regex fails to compile.
     */
    public static PermissionPolicy compile(PermissionConfig config, List<String> floorPathDeny) {
        List<PathMatcher> pDeny = new ArrayList<>();
        for (String glob : floorPathDeny) {
            pDeny.add(PermissionPaths.globMatcher(glob));
        }
        for (String glob : config.getPaths().getDeny()) {
            pDeny.add(PermissionPaths.globMatcher(glob));
        }
        List<PathMatcher> pAllow = new ArrayList<>();
        for (String glob : config.getPaths().getAllow()) {
            pAllow.add(PermissionPaths.globMatcher(glob));
        }
        List<Pattern> cDeny = compileRegex(config.getCommands().getDeny());
        List<Pattern> cAllow = compileRegex(config.getCommands().getAllow());
        return new PermissionPolicy(pDeny, pAllow, cDeny, cAllow);
    }

    private static List<Pattern> compileRegex(List<String> patterns) {
        List<Pattern> compiled = new ArrayList<>(patterns.size());
        for (String regex : patterns) {
            try {
                compiled.add(Pattern.compile(regex));
            } catch (PatternSyntaxException e) {
                throw new PermissionConfigException(
                        "Invalid command permission regex: '" + regex + "' — " + e.getMessage(), e);
            }
        }
        return compiled;
    }

    /** deny → allow → ask against the canonical subject path. */
    public PermissionDecision evaluatePath(Path canonical) {
        for (PathMatcher m : pathDeny) {
            if (m.matches(canonical)) return PermissionDecision.DENY;
        }
        for (PathMatcher m : pathAllow) {
            if (m.matches(canonical)) return PermissionDecision.ALLOW;
        }
        return PermissionDecision.ASK;
    }

    /** deny → allow → ask against the raw command string. */
    public PermissionDecision evaluateCommand(String command) {
        for (Pattern p : commandDeny) {
            if (p.matcher(command).find()) return PermissionDecision.DENY;
        }
        for (Pattern p : commandAllow) {
            if (p.matcher(command).find()) return PermissionDecision.ALLOW;
        }
        return PermissionDecision.ASK;
    }
}
