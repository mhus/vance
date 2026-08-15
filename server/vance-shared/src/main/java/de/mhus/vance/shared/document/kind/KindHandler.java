package de.mhus.vance.shared.document.kind;

import de.mhus.vance.shared.document.kind.validate.Finding;
import de.mhus.vance.shared.document.kind.validate.KindValidationContext;
import java.util.List;

/**
 * Service for a document {@code kind}. A bean implementing this interface
 * declares that the {@code kind} name it returns from {@link #getName()} is
 * a valid Vance document kind — known to {@code doc_write}, surfaced in tool
 * descriptions, accepted by {@link de.mhus.vance.shared.document.KindRegistry}.
 *
 * <p>Beyond registration the handler is a <b>capability-carrying service per
 * kind</b>. {@link #validate} is the first of several planned capabilities
 * (later: codec normalisation, format migration, metadata / link extraction)
 * — a {@code KindHandler} is a service, not a bare validator. New capabilities
 * grow on this interface as default methods so existing implementations keep
 * compiling.
 *
 * <p>Addons add a new kind by exposing a {@code KindHandler} bean (in their
 * {@code @ComponentScan}-ed package); built-in kinds register through
 * {@link de.mhus.vance.shared.document.BuiltInKindHandlers}.
 *
 * <p>Pure interface: no Spring imports, no DocumentService dependency.
 * Kept inside the codec-only {@code kind} package so any addon that
 * already depends on {@code vance-shared} can implement it.
 */
public interface KindHandler {

    /** Canonical kind name as it appears in document front-matter
     *  (lower-case, no spaces, e.g. {@code "diagram"},
     *  {@code "calendar"}). */
    String getName();

    /**
     * Validate {@code content} against this kind. Default: no checks —
     * structural parse errors are surfaced by the codec elsewhere and a kind
     * without semantic invariants is considered valid. A kind opts into
     * semantic validation by overriding this method (mirrors the
     * {@code BlockValidator} SPI: add a validatable kind = override
     * {@code validate}, no central switch).
     *
     * <p>Advisory only: findings never block a write. {@code ERROR}-level
     * findings mean the content is malformed for this kind; {@code WARNING}s
     * are hints. Reference-existence / cross-kind checks go through
     * {@link KindValidationContext#docs()}.
     */
    default List<Finding> validate(String content, KindValidationContext ctx) {
        return List.of();
    }

    /**
     * Does this kind recognise {@code content} as its own, without being
     * told? Default: no — a kind opts in.
     *
     * <p>Used when a document is created without an explicit {@code kind}.
     * The alternative was a rule in {@code doc_write} ("a mermaid fence means
     * diagram"), which would put knowledge about one kind's on-disk shape
     * into a tool that must stay kind-agnostic, and would have to grow a
     * branch per kind. The handler already owns that knowledge.
     *
     * <p><b>First match wins</b>, in {@link #detectionPriority()} order.
     * Short bodies are genuinely ambiguous — {@code - a\n- b} is a plausible
     * list, checklist, tree and mindmap at once — so requiring a unique
     * claimant would mean no detection in exactly the common case. Order
     * decides instead, which is why it is declared rather than inherited from
     * bean-injection order.
     *
     * <p>Claim narrowly. A detector that matches loosely wins over more
     * specific kinds that sort after it, and the write is then typed wrong
     * with no error anywhere. Prefer a marker that only this kind's canonical
     * on-disk form carries. {@code text} must never claim anything: it is the
     * fallback when nothing matches.
     *
     * <p>Measured motivation: across three models, 7 of 27 stored Mermaid
     * documents ended up as {@code kind: text} because the parameter is
     * optional and defaults to text when omitted. The information was in the
     * tool description; the default was stronger. See
     * {@code planning/model-context-inflation-lab.md}.
     */
    default boolean detects(String content) {
        return false;
    }

    /**
     * Order in which {@link #detects} is consulted — lower runs first, ties
     * broken by kind name so the sequence is total and stable.
     *
     * <p>Explicit because the registry receives its handlers as a
     * Spring-collected list, whose order depends on bean names and classpath
     * scanning. "First wins" over that list would mean a different winner
     * after an addon is deployed, for the same document. Priority makes the
     * winner a property of the kinds, not of the deployment.
     *
     * <p>Convention: 0–99 for kinds with an unmistakable marker (a
     * {@code ```mermaid} fence, a typed manifest key), 100 (default) for
     * ordinary kinds, 900+ for deliberately greedy fallbacks.
     */
    default int detectionPriority() {
        return 100;
    }
}
