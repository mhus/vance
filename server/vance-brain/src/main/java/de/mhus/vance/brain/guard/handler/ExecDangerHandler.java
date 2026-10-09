package de.mhus.vance.brain.guard.handler;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * The {@code exec-danger} guard handler — the TOOL-point danger gate:
 * before an {@code exec_run} call (or its {@code work_}/{@code client_}
 * backend) executes, it scans the command string for <b>clearly
 * catastrophic</b> shell patterns and denies the call. The denial is the
 * handler's whole job — the model receives the reason as a tool error
 * and can adjust the command (fail-closed per point).
 *
 * <p>The default pattern list is deliberately narrow: every default must
 * be catastrophic with next to no legitimate use case, because a false
 * positive blocks the agent's work. Anything debatable ({@code rm -rf}
 * on a scratch directory, {@code git push --force} on an own branch,
 * {@code sudo}) stays out — projects add their own patterns via the
 * {@code patterns} param:
 *
 * <pre>{@code
 * guard:
 *   - handler: exec-danger
 *     params:
 *       patterns: ['\bgit\s+push\s+.*--force\b']
 * }</pre>
 *
 * <p>The patterns match the raw {@code command} arg (full shell syntax
 * is allowed there), case-insensitive, anywhere in the string. A custom
 * {@code patterns} list <b>replaces</b> the defaults — compose, don't
 * merge, so a strict project can also loosen the gate deliberately.
 */
@Service
public class ExecDangerHandler implements GuardHandler {

    /**
     * Default danger patterns — see the class javadoc for the "clearly
     * catastrophic" bar each one had to clear.
     */
    private static final List<String> DEFAULT_PATTERNS = List.of(
            // rm with recursive AND force — in ANY flag form: one token
            // (-rf/-fr), split tokens (-r -f), long form (--recursive
            // --force), flags after the operand (rm / -r -f is valid rm
            // syntax). The two lookaheads each demand one of the flags
            // somewhere in the command segment; the target must be a
            // top-level path: /, ~, $HOME or *.
            "\\brm\\b(?=[^;|&]*\\s-{1,2}[a-zA-Z]*r)(?=[^;|&]*\\s-{1,2}[a-zA-Z]*f)[^;|&]*\\s(?:--\\s+)?(?:/|~|\\$HOME|\\*)",
            // fork bomb
            ":\\(\\)\\s*\\{.*\\|.*&.*\\}\\s*;",
            // raw-device writes: dd to a device, mkfs, redirect to a disk
            "\\bdd\\b[^|;&]*\\bof=/dev/",
            "\\bmkfs\\b",
            ">\\s*/dev/(?:sd|nvme|hd|vd)",
            // remote payload piped straight into a shell — bare, behind
            // sudo/doas, or by absolute path (/bin/sh); the shell prefixes
            // stay enumerated so 'wash'/'fishing' style words cannot match
            "\\b(?:curl|wget)\\b[^|;&]*\\|\\s*(?:(?:sudo|doas)\\s+)*(?:[^\\s|;&]*\\/)?(?:ba|z|fi|da)?sh\\b",
            // power control
            "\\b(?:shutdown|reboot|halt|poweroff)\\b");

    @Override
    public String name() {
        return "exec-danger";
    }

    @Override
    public String description() {
        return "Danger gate for exec_run commands: denies clearly catastrophic shell commands "
                + "(rm -rf /, fork bombs, raw-device writes, pipe-to-shell from curl/wget, "
                + "shutdown) before they execute. Extend via the 'patterns' param.";
    }

    @Override
    public void onTool(GuardContext ctx) {
        GuardToolCall call = ctx.tool();
        if (call == null) {
            return;
        }
        Object commandArg = call.args().get("command");
        if (!(commandArg instanceof String command) || StringUtils.isBlank(command)) {
            // Not a command-carrying exec call (or an odd shape) — nothing to judge.
            return;
        }
        for (Pattern pattern : compile(ctx.params().get("patterns"))) {
            var m = pattern.matcher(command);
            if (m.find()) {
                ctx.deny("the command matches a dangerous pattern ("
                        + m.group().trim() + ") — rewrite it, drop the destructive part, "
                        + "or ask the user to run it themselves: " + command);
                return;
            }
        }
    }

    /**
     * Compiles the params-supplied pattern list, falling back to the
     * defaults when absent or empty. A broken regex is a config bug —
     * it fails the guard run (fail-closed at the TOOL point), not
     * silently weakens the gate.
     */
    private static List<Pattern> compile(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return DEFAULT_PATTERNS.stream()
                    .map(p -> Pattern.compile(p, Pattern.CASE_INSENSITIVE))
                    .toList();
        }
        List<Pattern> out = new ArrayList<>();
        for (Object item : list) {
            out.add(Pattern.compile(String.valueOf(item), Pattern.CASE_INSENSITIVE));
        }
        return List.copyOf(out);
    }
}
