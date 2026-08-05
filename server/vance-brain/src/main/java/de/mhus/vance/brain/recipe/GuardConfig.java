package de.mhus.vance.brain.recipe;

import java.util.Map;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;

/**
 * One completion guard: a JS guard script plus where it fires and its
 * loop cap. Config-level (recipe {@code guard:} block or a per-process
 * runtime override). The script decides judge + action imperatively via
 * the {@code vance.guard.*} surface — see
 * {@code planning/completion-guard.md} v2.
 *
 * <p>Exactly one script source is set: {@link #scriptPath} (document
 * cascade) or inline {@link #scriptBody}. {@link #params} are handed to
 * the script as {@code vance.params.*} — this is how a reusable bundled
 * guard (e.g. {@code _vance/guards/llm-judge.js}) is configured without
 * writing JS.
 *
 * @param scriptPath guard-script document-cascade path (null in inline shape)
 * @param scriptBody inline guard-script body (null in path shape)
 * @param params     inputs exposed to the script as {@code vance.params.*}
 * @param allowTools grant the process's full tool surface (default: a
 *                   supervisor surface — llm/documents/process only)
 * @param trigger    which yield point this guard applies to
 * @param maxRounds  hard cap on guard injections for the process (0 = disabled)
 */
public record GuardConfig(
        @Nullable String scriptPath,
        @Nullable String scriptBody,
        @Nullable Map<String, Object> params,
        boolean allowTools,
        GuardTrigger trigger,
        int maxRounds) {

    public GuardConfig {
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
    public static GuardConfig scriptPath(
            String scriptPath, boolean allowTools, GuardTrigger trigger, int maxRounds) {
        return new GuardConfig(scriptPath, null, Map.of(), allowTools, trigger, maxRounds);
    }

    /** Guard script from a document-cascade path with script params. */
    public static GuardConfig scriptPath(
            String scriptPath, @Nullable Map<String, Object> params,
            boolean allowTools, GuardTrigger trigger, int maxRounds) {
        return new GuardConfig(scriptPath, null, params, allowTools, trigger, maxRounds);
    }

    /** Guard script from an inline body. */
    public static GuardConfig scriptBody(
            String scriptBody, boolean allowTools, GuardTrigger trigger, int maxRounds) {
        return new GuardConfig(null, scriptBody, Map.of(), allowTools, trigger, maxRounds);
    }
}
