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
 *   <li>Blank input → {@code existingKind} when it is a specific kind (on
 *       update). An existing {@code text} is not a decision (it is also the
 *       silent create fallback), so detection may still re-type it.</li>
 *   <li>…then detection: the kind claiming the document's <em>path</em> first
 *       (config trees, {@link de.mhus.vance.shared.document.kind.KindHandler#detectsPath}),
 *       then the kind claiming the <em>body</em>
 *       ({@link de.mhus.vance.shared.document.kind.KindHandler#detects}). Location beats body
 *       shape — see {@link KindRegistry#detectKind(String, String)}.</li>
 *   <li>…then {@code existingKind} (on update) or {@code "text"} (on create).</li>
 *   <li>An explicit {@code text} stays {@code text} — unless the document's <em>path</em> is
 *       claimed by a kind: location beats a requested {@code text}, the body never does.</li>
 *   <li>Any other explicit kind resolves against the {@link KindRegistry}: exact
 *       case-insensitive match, then substring match — the request <em>contains</em>
 *       a registered kind name (so {@code "diagramm"} → {@code "diagram"},
 *       {@code "user-mindmap"} → {@code "mindmap"}). Only the
 *       request-contains-name direction — the reverse is too eager
 *       and silently rewrites things like {@code "li"} to
 *       {@code "list"}.</li>
 *   <li>Unresolvable explicit kind → {@code existingKind} (on update) or
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
     * <p>Only consulted when the caller names no kind and the existing
     * document carries no <em>specific</em> kind: a requested kind — even
     * {@code text} — is a decision, and so is an existing specific kind. An
     * existing {@code text} is not (it is also what an un-kinded create fell
     * back to), so an overwrite without {@code kind} is re-detected and can
     * end up as {@code diagram} once the body grows a ```mermaid fence.
     * Pinned by {@code KindResolverTest.existingText_isReplacedByDetection}.
     */
    public String resolve(@Nullable String requested, @Nullable String existingKind, @Nullable String content) {
        return resolve(requested, existingKind, content, /*documentPath*/ null);
    }

    public String resolve(
            @Nullable String requested,
            @Nullable String existingKind,
            @Nullable String content,
            @Nullable String documentPath) {
        String norm = requested == null ? "" : requested.trim().toLowerCase();
        String existing = existingKind == null ? "" : existingKind.trim().toLowerCase();

        // A requested `text` is a decision, like any other explicit kind:
        // detection only runs when the caller names no kind at all.
        //
        // History — if mis-typed diagrams come back, read this first. From
        // 2026-08-15 (d1af5ea62) a requested `text` counted as "unspecified"
        // and let detection claim the body. Reason: in a MermaidVariety run
        // (gemma4) 30 of 33 doc_write calls passed an explicit kind, and every
        // mis-typed diagram carried kind=text next to a ```mermaid body —
        // models picked `text` when they did not decide. Loosening lifted the
        // run from 3–4/9 to 8/9.
        // Cost of that loosening: a deliberate `text` was overruled too. Prose
        // with one embedded ```mermaid block was stored as `diagram` (the prose
        // shrinks to _preamble/_postamble in the diagram editor) and failed the
        // diagram self-check — GitHub issue #70. Models have since become
        // reliable at choosing the kind, so the explicit `text` wins again.
        // Re-loosening means: add `|| FALLBACK_KIND.equals(norm)` here, and
        // first narrow DiagramCodec.looksLikeDiagram to diagram-only bodies,
        // otherwise #70 returns.
        if (norm.isEmpty()) {
            if (!existing.isEmpty() && !FALLBACK_KIND.equals(existing)) {
                return existing;
            }
            String detected = registry.detectKind(documentPath, content);
            if (detected != null) {
                return detected;
            }
            return !existing.isEmpty() ? existing : FALLBACK_KIND;
        }

        if (FALLBACK_KIND.equals(norm)) {
            return resolveRequestedText(documentPath);
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

    /**
     * A requested {@code text}: a decision against <em>body</em> detection
     * (#70), but not against the document's <em>location</em>. A path claim
     * ({@link de.mhus.vance.shared.document.kind.KindHandler#detectsPath} —
     * recipes, research/feed/mount sources, hooks, guards, models, prompts,
     * wizards) says what every document in that config tree is; written as
     * {@code text} it would skip the kind's validation silently, and an
     * overwrite of an existing source with {@code kind=text} would downgrade
     * it. So the path claim wins — the kind's validator then reports a
     * malformed body instead of the write slipping past it. Only the path is
     * asked; the body never re-types a requested {@code text}.
     */
    private String resolveRequestedText(@Nullable String documentPath) {
        if (documentPath == null || documentPath.isBlank()) {
            return FALLBACK_KIND;
        }
        String claimed = registry.detectKind(documentPath, /*content*/ null);
        return claimed != null ? claimed : FALLBACK_KIND;
    }
}
