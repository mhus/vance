package de.mhus.vance.toolpack.exec;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Allow-list policy for subprocess environments. Both exec surfaces — the
 * brain's {@code work_exec_run} and the foot's {@code client_exec_run} — spawn
 * shell commands for an agent, and the parent process holds credentials the
 * agent must never see (Mongo password, encryption password, provider API
 * keys, the internal token; on a workstation: SSH agent, cloud credentials).
 * A subprocess therefore starts from a small non-secret base and gets only
 * what was explicitly granted on top.
 *
 * <p><b>Allow-list, never deny-list.</b> A deny-list is a bet on completeness
 * — the next credential variable added to the deployment is silently exposed.
 * Here the next variable is silently <em>not</em> exposed, which is the safe
 * direction of silence (same reasoning as the web-grab file naming).
 *
 * <p><b>Orthogonal to the command gate.</b> The sandbox decides <em>whether</em>
 * a command runs; this policy decides <em>what it may see</em> once running.
 * Turning the sandbox off does not re-open the environment — the pass-through
 * list is the only widening, and it is an explicit configuration act.
 *
 * <p>Shared by brain and foot via {@code vance-toolpack}, which hangs on both
 * ends and therefore carries no server infrastructure. The same base is used
 * for MCP subprocesses ({@code McpStdioTransport}); keep the two in sync by
 * referencing {@link #INHERITABLE_NAMES}.
 */
public final class ExecEnvPolicy {

    /**
     * Non-secret variables every subprocess may inherit so common tooling
     * keeps working: locating binaries ({@code PATH}), locale, time zone and
     * temp dirs, plus the Windows equivalents. Deliberately no credential
     * variables and no identity variables — anything else is a pass-through
     * decision at the call site or in configuration.
     */
    public static final Set<String> INHERITABLE_NAMES = Set.of(
            "PATH",
            "HOME",
            "LANG",
            "LC_ALL",
            "LC_CTYPE",
            "TZ",
            "TMPDIR",
            "TMP",
            "TEMP",
            "SystemRoot",
            "USERPROFILE",
            // The exec-context marker the manuals read *inside* the child
            // (manual self-installed-tools: echo exec env: ${VANCE_EXEC_ENV:-workstation}
            // — set = commands run in the brain container, unset = workstation).
            // Non-secret; without it the agent always sees workstation and
            // applies the wrong install pattern inside the container.
            "VANCE_EXEC_ENV");

    private ExecEnvPolicy() {}

    /**
     * Builds the child environment from {@code parentEnv}: the {@link
     * #INHERITABLE_NAMES} base plus {@code extraNames} the caller's
     * configuration granted, then nothing else. A granted name that is not
     * set in the parent is simply absent — the policy copies, it never
     * invents values.
     *
     * <p>The result is a fresh mutable map so callers may layer their own
     * variables on top (script-run tokens, a scoped {@code HOME}).
     */
    public static Map<String, String> seal(Map<String, String> parentEnv, Collection<String> extraNames) {
        Map<String, String> out = new LinkedHashMap<>();
        copy(out, parentEnv, INHERITABLE_NAMES);
        if (extraNames != null) {
            copy(out, parentEnv, extraNames);
        }
        return out;
    }

    private static void copy(Map<String, String> out, Map<String, String> parentEnv, Collection<String> names) {
        for (String name : names) {
            String value = parentEnv.get(name);
            if (value != null) {
                out.put(name, value);
            }
        }
    }
}
