package de.mhus.vance.api.vogon;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Explicit strategy-result declaration. Set as the top-level
 * {@code result:} block in a strategy-YAML; replaces the implicit
 * Markdown-concatenation default that {@code summarizeForParent}
 * builds from phase artifacts.
 *
 * <p>Specified in {@code specification/vogon-engine.md} §3.2. The
 * engine evaluates {@link #fields} first (type-preserved against
 * {@code ${params/state/phases.X.artifacts/flags}}), then renders
 * {@link #text} as a string with the additional {@code ${result.X}}
 * scope referring to the just-evaluated fields. Result is emitted
 * to the parent via {@code ctx.emitReply(text, …, fields)}.
 *
 * <p>{@link #onFailure} is the symmetric block for the
 * FAILED-strategy path. When absent, FAILED falls back to the
 * legacy {@code summarizeForParent} default.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResultSpec {

    /**
     * Structured result fields. Each value is a {@code ${...}}
     * template evaluated against {@code params}, {@code state},
     * {@code phases.<phase>.artifacts}, {@code flags}. A template
     * that is exactly one {@code ${...}} preserves the source type
     * (Number, Boolean, List, Map, null); interpolated templates
     * always produce {@code String}. The evaluated map lands as the
     * REPLY {@code payload} sent to the parent.
     *
     * <p>{@code ${result.X}} is NOT valid here — it's a scope only
     * exposed inside {@link #text} (and {@link #onFailure}.text).
     * The strategy-load validator rejects cyclic references.
     */
    @Builder.Default
    private Map<String, String> fields = new LinkedHashMap<>();

    /**
     * User-facing reply text. Rendered to {@code String} via standard
     * {@code ${...}} substitution plus the {@code ${result.X}} scope
     * that references the just-evaluated {@link #fields}. Becomes the
     * REPLY {@code content} sent to the parent (the receiving engine
     * — typically Arthur — uses it as RELAY body).
     *
     * <p>{@code null} or blank means "no formulated text" — the
     * engine then falls back to {@code summarizeForParent}'s default
     * Markdown concatenation for the REPLY content, while the
     * {@link #fields} payload is still shipped.
     */
    private @Nullable String text;

    /**
     * Optional FAILED-path block. Same shape as the parent
     * {@code result:}; engine picks it when the strategy ends with
     * a FAILED outcome. When {@code null}, the legacy
     * {@code summarizeForParent} default kicks in for FAILED. Nested
     * {@code onFailure} on {@code onFailure} is ignored (no cascade).
     */
    private @Nullable ResultSpec onFailure;
}
