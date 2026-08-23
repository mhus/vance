package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.shared.document.KindRegistry;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Coerces an LLM-supplied {@code kind} string to a registered kind
 * name without throwing. The whole point of this class is that
 * {@code doc_write} must NEVER fail because the model wrote
 * {@code "diagramm"} or {@code "MERMAID"} or left the field blank —
 * silent best-effort resolution beats a hard error in user-facing
 * tools.
 *
 * <h2>Resolution order</h2>
 * <ol>
 *   <li>Blank input → {@code existingKind} (on update) or {@code "text"}
 *       (on create).</li>
 *   <li>Exact case-insensitive match against the {@link KindRegistry}.</li>
 *   <li>Substring match: the request <em>contains</em> a registered
 *       kind name (so {@code "diagramm"} → {@code "diagram"},
 *       {@code "user-mindmap"} → {@code "mindmap"}). Only the
 *       request-contains-name direction — the reverse is too eager
 *       and silently rewrites things like {@code "li"} to
 *       {@code "list"}.</li>
 *   <li>Unresolvable → {@code existingKind} (on update) or
 *       {@code "text"} (on create).</li>
 * </ol>
 *
 * <p>The fallback to {@code "text"} on create is deliberately hidden
 * from the tool schema and from manuals so the LLM treats {@code kind}
 * as mandatory. Stating the fallback in the docs would make the model
 * lazily omit it.
 */
@Service
public class KindResolver {

    private static final String FALLBACK_KIND = "text";

    private final KindRegistry registry;

    public KindResolver(KindRegistry registry) {
        this.registry = registry;
    }

    /**
     * Resolve {@code requested} to a registered kind name.
     *
     * @param requested    the kind string the LLM passed (may be
     *                     {@code null}, blank, garbage, or correct)
     * @param existingKind the kind already on the document being
     *                     updated, or {@code null} when creating
     * @return a non-null, lower-cased kind name from the registry, or
     *         {@code "text"} as the silent ultimate fallback
     */
    public String resolve(@Nullable String requested, @Nullable String existingKind) {
        return resolve(requested, existingKind, /*content*/ null);
    }

    /**
     * As {@link #resolve(String, String)}, but lets the registered kinds
     * claim un-typed content on create.
     *
     * <p>The {@code kind} parameter is optional and documented as defaulting
     * to {@code text}; a model that omits it therefore stores a Mermaid
     * document as prose. Measured: 7 of 27 such documents across three models
     * (see {@code planning/model-context-inflation-lab.md}). Asking the
     * {@link KindRegistry} closes that path without teaching this resolver
     * anything about individual kinds — the handler owns its marker.
     *
     * <p>Only consulted when neither side names a <em>specific</em> kind: a
     * requested kind is a decision, and so is the kind an existing document
     * already carries. {@code text} is neither — it is the name of
     * "unspecified" (see below), so an overwrite of a {@code kind: text}
     * document is re-detected and can end up as {@code diagram} once the
     * body grows a ```mermaid fence. Pinned by
     * {@code KindResolverTest.existingText_isReplacedByDetection}.
     */
    public String resolve(
            @Nullable String requested,
            @Nullable String existingKind,
            @Nullable String content) {
        String norm = requested == null ? "" : requested.trim().toLowerCase();
        String existing = existingKind == null ? "" : existingKind.trim().toLowerCase();

        // `text` is not a commitment — it is the name of "unspecified", and
        // it is what a model picks when it does not decide. Measured: of 33
        // doc_write calls in one MermaidVariety run, 30 passed an explicit
        // kind, and every mis-typed diagram carried kind=text next to a
        // ```mermaid body. Treating it as a decision meant the registered
        // kinds never got asked. A specific kind still wins outright.
        if (norm.isEmpty() || FALLBACK_KIND.equals(norm)) {
            if (!existing.isEmpty() && !FALLBACK_KIND.equals(existing)) {
                return existing;
            }
            String detected = registry.detectKind(content);
            if (detected != null) {
                return detected;
            }
            return !existing.isEmpty() ? existing : FALLBACK_KIND;
        }

        if (registry.isKnown(norm)) {
            return norm;
        }

        // Substring heuristic — only request-contains-registered, not
        // the reverse, to avoid silently rewriting partials like "li"
        // → "list".
        for (String known : registry.names()) {
            if (norm.contains(known)) {
                return known;
            }
        }

        // Unresolvable: keep existing on update, fall back to text on create.
        return !existing.isEmpty() ? existing : FALLBACK_KIND;
    }
}
