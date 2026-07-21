package de.mhus.vance.shared.document.kind;

import de.mhus.vance.shared.document.kind.validate.Finding;
import de.mhus.vance.shared.document.kind.validate.KindValidationContext;
import java.util.List;

/**
 * Service for a document {@code kind}. A bean implementing this interface
 * declares that the {@code kind} name it returns from {@link #getName()} is
 * a valid Vance document kind — known to {@code doc_create}, surfaced in tool
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
}
