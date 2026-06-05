package de.mhus.vance.shared.document.kind;

/**
 * Spring-discovered registration marker for a document {@code kind}. A
 * bean implementing this interface declares that the {@code kind} name
 * it returns from {@link #getName()} is a valid Vance document kind —
 * known to {@code doc_create}, surfaced in tool descriptions, accepted
 * by {@link de.mhus.vance.shared.document.KindRegistry}.
 *
 * <p>The interface is deliberately empty beyond the name. Addons add a
 * new kind by exposing a {@code KindHandler} bean (in their
 * {@code @ComponentScan}-ed package); built-in kinds register through
 * {@link de.mhus.vance.shared.document.BuiltInKindHandlers}. Future
 * extension points (default stub body, MIME-type, validation hook) can
 * grow on this interface as default methods without breaking existing
 * implementations.
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
}
