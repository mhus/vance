package de.mhus.vance.brain.guard.handler;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * The {@code fence-check} guard handler — a deterministic, no-LLM
 * validator for fenced blocks in the final output (the classic
 * "are the ``` fences set correctly" completion guard). Finds:
 *
 * <ul>
 *   <li><b>unclosed fences</b> — the output ends while a fence opened
 *       at line N is still open;</li>
 *   <li><b>empty fences</b> — an opening fence whose body holds no
 *       non-blank line (an info string without a body is almost always
 *       a mangled block).</li>
 * </ul>
 *
 * <p>On a finding it asks the process to repair the output via
 * cap-aware {@link GuardContext#continueWith(String)}. Purely
 * structural by design: unknown {@code vance-*} fence kinds are
 * <b>not</b> findings — unregistered kinds render as a placeholder by
 * contract (workpage spec, "Ad-hoc Fence-Typen"), and content-level
 * judging belongs to an LLM guard script, not to a shipped default.
 *
 * <p>Fence scanning follows the CommonMark rules the block editors
 * rely on: an opening fence is a line of 3+ backticks (up to 3 leading
 * spaces) with an optional info string; a closing fence is a line of
 * only backticks, at least as many as the opening one (so a
 * 4-backtick {@code vance-columns} fence keeps 3-backtick content
 * fences as content).
 */
@Service
public class FenceCheckHandler implements GuardHandler {

    /** Opening fence: 3+ backticks, optional info string, no backticks in the info. */
    private static final Pattern FENCE_OPEN = Pattern.compile("^ {0,3}`{3,}([^`]*)$");

    @Override
    public String name() {
        return "fence-check";
    }

    @Override
    public String description() {
        return "Deterministic fence validation of the final output: flags unclosed and empty "
                + "```-fenced blocks and asks the process to repair them.";
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
        if (StringUtils.isBlank(ctx.output())) {
            return;
        }
        List<String> findings = scanFences(ctx.output());
        if (findings.isEmpty()) {
            return;
        }
        ctx.continueWith("Your final output contains malformed fenced blocks: " + String.join("; ", findings)
                + ". Repair the fences (every opening fence needs a closing one, every body needs content) "
                + "and deliver the corrected output.");
    }

    /**
     * One pass over the output lines; returns the human-readable
     * findings (empty = output is clean).
     */
    private static List<String> scanFences(String output) {
        List<String> findings = new ArrayList<>();
        String[] lines = output.split("\n", -1);
        int openLineNumber = -1;
        int openTicks = 0;
        String openInfo = "";
        boolean hasBody = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (openLineNumber < 0) {
                Matcher m = FENCE_OPEN.matcher(line);
                if (m.matches()) {
                    openLineNumber = i + 1;
                    openTicks = leadingBackticks(line);
                    openInfo = m.group(1).trim();
                    hasBody = false;
                }
            } else if (isClosingFence(line, openTicks)) {
                if (!hasBody) {
                    findings.add("fence '" + openInfo + "' at line " + openLineNumber + " has an empty body");
                }
                openLineNumber = -1;
            } else if (!line.isBlank()) {
                hasBody = true;
            }
        }
        if (openLineNumber >= 0) {
            findings.add("fence '" + openInfo + "' at line " + openLineNumber + " is never closed");
        }
        return findings;
    }

    /** A line of only backticks (plus surrounding whitespace), at least {@code minTicks} long. */
    private static boolean isClosingFence(String line, int minTicks) {
        String stripped = line.strip();
        if (stripped.length() < minTicks) {
            return false;
        }
        for (int i = 0; i < stripped.length(); i++) {
            if (stripped.charAt(i) != '`') {
                return false;
            }
        }
        return true;
    }

    private static int leadingBackticks(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == '`') {
            count++;
        }
        return count;
    }
}
