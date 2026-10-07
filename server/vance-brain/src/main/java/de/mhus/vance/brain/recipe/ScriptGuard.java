package de.mhus.vance.brain.recipe;

import java.util.Map;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;

/**
 * A Shooty guard implemented as a JS guard script. Exactly one script
 * source is set: {@link #scriptPath} (document cascade) or inline
 * {@link #scriptBody}. The script decides judge + action imperatively
 * through the {@code vance.guard.*} surface — see
 * {@code specification/public/shooty.md}.
 *
 * @param scriptPath guard-script document-cascade path (null in inline shape)
 * @param scriptBody inline guard-script body (null in path shape)
 * @param params     inputs exposed to the script as {@code vance.params.*}
 * @param allowTools grant the process's full tool surface (default: a
 *                   supervisor surface — llm/documents/process only)
 * @param trigger    the {@link GuardPoint} this guard fires at (the YAML
 *                   field keeps the name {@code trigger}; a script guard
 *                   fires at exactly one point — {@code both} being the
 *                   legacy alias for the stop/terminate pair)
 * @param maxRounds  hard cap on guard injections for the process
 *                   (0 = disabled; only evaluated at STOP/TERMINATE)
 */
public record ScriptGuard(
        @Nullable String scriptPath,
        @Nullable String scriptBody,
        Map<String, Object> params,
        boolean allowTools,
        GuardPoint trigger,
        int maxRounds)
        implements GuardConfig {

    public ScriptGuard {
        Objects.requireNonNull(trigger, "guard.trigger");
        if (maxRounds < 0) {
            throw new IllegalArgumentException("guard.maxRounds must be >= 0");
        }
        boolean hasPath = StringUtils.isNotBlank(scriptPath);
        boolean hasBody = StringUtils.isNotBlank(scriptBody);
        if (hasPath == hasBody) {
            throw new IllegalArgumentException(
                    "guard requires exactly one script source: either 'script' or 'scriptBody'");
        }
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    /** Guard script from a document-cascade path. */
    public static ScriptGuard ofPath(String scriptPath, boolean allowTools, GuardPoint trigger, int maxRounds) {
        return new ScriptGuard(scriptPath, null, Map.of(), allowTools, trigger, maxRounds);
    }

    /** Guard script from a document-cascade path with script params. */
    public static ScriptGuard ofPath(
            String scriptPath,
            @Nullable Map<String, Object> params,
            boolean allowTools,
            GuardPoint trigger,
            int maxRounds) {
        return new ScriptGuard(scriptPath, null, params, allowTools, trigger, maxRounds);
    }

    /** Guard script from an inline body. */
    public static ScriptGuard ofBody(String scriptBody, boolean allowTools, GuardPoint trigger, int maxRounds) {
        return new ScriptGuard(null, scriptBody, Map.of(), allowTools, trigger, maxRounds);
    }

    @Override
    public boolean firesOnStart() {
        return trigger.firesOnStart();
    }

    @Override
    public boolean firesOnCommand() {
        return trigger.firesOnCommand();
    }

    @Override
    public boolean firesOnNaturalStop() {
        return trigger.firesOnNaturalStop();
    }

    @Override
    public boolean firesOnTerminate() {
        return trigger.firesOnTerminate();
    }
}
