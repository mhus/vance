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
 * <p>The {@code delete} domain is the one asymmetric case: its
 * <em>deny</em> side inherits the path denies (floor included), while its
 * <em>allow</em> side stands alone. Denying a tree must keep denying it
 * for every operation, but allowing a tree to be read must not allow it
 * to be emptied — see {@link #evaluateDelete}.
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
    private final List<PathMatcher> deleteDeny;
    private final List<PathMatcher> deleteAllow;

    private PermissionPolicy(
            List<PathMatcher> pathDeny,
            List<PathMatcher> pathAllow,
            List<Pattern> commandDeny,
            List<Pattern> commandAllow,
            List<PathMatcher> deleteDeny,
            List<PathMatcher> deleteAllow) {
        this.pathDeny = List.copyOf(pathDeny);
        this.pathAllow = List.copyOf(pathAllow);
        this.commandDeny = List.copyOf(commandDeny);
        this.commandAllow = List.copyOf(commandAllow);
        this.deleteDeny = List.copyOf(deleteDeny);
        this.deleteAllow = List.copyOf(deleteAllow);
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
        List<PathMatcher> dDeny = new ArrayList<>();
        for (String glob : config.getDelete().getDeny()) {
            dDeny.add(PermissionPaths.globMatcher(glob));
        }
        List<PathMatcher> dAllow = new ArrayList<>();
        for (String glob : config.getDelete().getAllow()) {
            dAllow.add(PermissionPaths.globMatcher(glob));
        }
        return new PermissionPolicy(pDeny, pAllow, cDeny, cAllow, dDeny, dAllow);
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

    /**
     * Verdict for {@code client_file_delete}. Denies cascade from the
     * path domain (floor + {@code paths.deny}) and are then extended by
     * {@code delete.deny}; only {@code delete.allow} can grant. A path
     * covered solely by {@code paths.allow} yields {@code ASK}, never
     * {@code ALLOW} — that is the whole point of the separate domain.
     */
    public PermissionDecision evaluateDelete(Path canonical) {
        for (PathMatcher m : pathDeny) {
            if (m.matches(canonical)) return PermissionDecision.DENY;
        }
        for (PathMatcher m : deleteDeny) {
            if (m.matches(canonical)) return PermissionDecision.DENY;
        }
        for (PathMatcher m : deleteAllow) {
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
