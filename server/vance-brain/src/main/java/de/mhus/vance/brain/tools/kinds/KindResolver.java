package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.shared.document.KindRegistry;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Coerces an LLM-supplied {@code kind} string to a registered kind
 * name without throwing. The whole point of this class is that
 * {@code doc_create} must NEVER fail because the model wrote
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
        String norm = requested == null ? "" : requested.trim().toLowerCase();
        String existing = existingKind == null ? "" : existingKind.trim().toLowerCase();

        if (norm.isEmpty()) {
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
