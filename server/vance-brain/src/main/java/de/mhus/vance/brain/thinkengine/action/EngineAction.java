package de.mhus.vance.brain.thinkengine.action;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Engine-level structured action — the parsed payload of one
 * {@code <engine>_action} tool call. Replaces free-form orchestration
 * tools (like {@code process_create} + {@code respond}) on engines
 * that opt in via {@link StructuredActionEngine}.
 *
 * <p><b>Why structured.</b> A free-form orchestrator emits text
 * <em>and</em> tool calls and a final-marker tool — three slots that
 * routinely conflict (filler messages, hallucinated names, premature
 * respond). One structured action collapses that to a single
 * discriminated value: the engine reads {@link #type()} to decide
 * what to do, with {@link #reason()} carrying the model's
 * explanation and {@link #params()} the type-specific payload.
 *
 * <p><b>Reason is mandatory.</b> Every action carries a non-blank
 * {@code reason} field — both for audit / observability and to force
 * the model to think about <em>why</em> it picked this branch. The
 * abstract base class rejects actions with missing or empty
 * {@code reason} via the JSON-validation correction loop.
 *
 * <p><b>Params shape</b>. {@code params} is a string-keyed map of
 * type-specific extras (e.g. {@code message}, {@code preset},
 * {@code prompt}). The base class doesn't validate them — that's the
 * subclass's job inside {@link
 * StructuredActionEngine#handleAction}, where it knows the per-type
 * required fields.
 */
public record EngineAction(
        String type,
        String reason,
        Map<String, Object> params) {

    /**
     * Convenience: returns the named param as a String, or
     * {@code null} when missing / not a string. The action loop
     * stores all params verbatim from the tool-call JSON, so primitives
     * arrive as their boxed types and unexpected shapes don't crash.
     */
    public @Nullable String stringParam(String key) {
        Object v = params == null ? null : params.get(key);
        return v instanceof String s ? s : null;
    }

    /** Same as {@link #stringParam} with a fallback. */
    public String stringParamOr(String key, String fallback) {
        String v = stringParam(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    public boolean booleanParam(String key, boolean fallback) {
        Object v = params == null ? null : params.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s.trim());
        return fallback;
    }
}
