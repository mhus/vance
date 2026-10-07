package de.mhus.vance.brain.guard.handler;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * The {@code exec-check} guard handler — the deterministic Java twin
 * of the bundled {@code dev-done.js} idea: at the yield point, if the
 * final output mentions a build/test/exec command but never reports
 * its result, the process is told to run it and report. Catches the
 * classic "I changed the code, done." completion.
 *
 * <p>Stateless by design: if the process still mentions unreported
 * commands after being asked, asking again is legitimate — the
 * {@code maxRounds} cap of the guard entry is the backstop.
 *
 * <p>Recipe {@code params}:
 * <ul>
 *   <li>{@code commands} — list of regexes for command mentions
 *       (defaults: the common build/test runners plus {@code wb build});</li>
 *   <li>{@code results} — list of regexes that count as a reported
 *       result (defaults: success/failure verdicts, exit codes,
 *       pass/fail counts, check marks). Broad defaults only widen the
 *       "a result was reported" exit, so a false positive there fails
 *       open — the guard stays quiet.</li>
 * </ul>
 */
@Service
public class ExecCheckHandler implements GuardHandler {

    /** Default {@code commands} regexes — command mentions to check. */
    private static final List<String> DEFAULT_COMMANDS = List.of(
            "\\bmvn\\b",
            "\\bmvnw\\b",
            "\\bgradle\\b",
            "\\bgradlew\\b",
            "\\bnpm( run)? (test|build)\\b",
            "\\byarn (test|build)\\b",
            "\\bpnpm (test|build)\\b",
            "\\bpytest\\b",
            "\\bgo (build|test)\\b",
            "\\bcargo (build|test)\\b",
            "\\bdocker build\\b",
            "\\bdotnet (build|test)\\b",
            "\\bwb build\\b");

    /** Default {@code results} regexes — markers of a reported result. */
    private static final List<String> DEFAULT_RESULTS = List.of(
            "BUILD SUCCESS",
            "BUILD FAILURE",
            "\\bpassed\\b",
            "\\bfailed\\b",
            "\\bfailures?\\b",
            "\\berrors?\\b",
            "\\bexit\\b",
            "\\bexited\\b",
            "[✔✘✗]");

    @Override
    public String name() {
        return "exec-check";
    }

    @Override
    public String description() {
        return "Checks the final output for build/exec command mentions without a reported result "
                + "and asks the process to run them and report the outcome.";
    }

    @Override
    public void onStop(GuardContext ctx) {
        check(ctx);
    }

    @Override
    public void onTerminate(GuardContext ctx) {
        check(ctx);
    }

    private void check(GuardContext ctx) {
        String output = ctx.output();
        if (StringUtils.isBlank(output)) {
            return;
        }
        List<Pattern> commands = compile(ctx.params().get("commands"), DEFAULT_COMMANDS);
        List<Pattern> results = compile(ctx.params().get("results"), DEFAULT_RESULTS);
        for (Pattern result : results) {
            if (result.matcher(output).find()) {
                // A result is reported — nothing to nag about.
                return;
            }
        }
        Set<String> mentioned = new LinkedHashSet<>();
        for (Pattern command : commands) {
            var m = command.matcher(output);
            if (m.find()) {
                mentioned.add(m.group().trim());
            }
        }
        if (mentioned.isEmpty()) {
            return;
        }
        ctx.continueWith("Your final output mentions " + String.join(", ", mentioned)
                + " but never reports their result. Run the mentioned commands and report the outcome "
                + "(success or failure, plus the exit code or a short result summary).");
    }

    /**
     * Compiles a params-supplied regex list, falling back to the
     * defaults when absent or empty. A broken regex is a config bug —
     * it fails the guard run (per-point fail strategy), not silently
     * weakens the check.
     */
    private static List<Pattern> compile(Object raw, List<String> defaults) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return defaults.stream().map(Pattern::compile).toList();
        }
        List<Pattern> out = new ArrayList<>();
        for (Object item : list) {
            out.add(Pattern.compile(String.valueOf(item)));
        }
        return List.copyOf(out);
    }
}
