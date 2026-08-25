/**
 * Injection contract for "which document is this Markdown from" — the
 * base a relative {@code vance:} reference resolves against.
 *
 * <p>A reference like {@code vance:analysis.yaml} means *the document next
 * to this one*, so resolving it needs to know which document "this one"
 * is. That is host knowledge: the renderer receives a string of Markdown
 * and has no idea where it came from.
 *
 * <p><b>Provided per document, not per page.</b> A Cortex tab is one
 * document and provides its path; an {@code EmbeddedKindBox} re-provides
 * the path of the document it embedded, so references inside embedded
 * content resolve next to *that* document rather than next to its host.
 * Nesting is exactly what makes {@code provide} the right mechanism here
 * — a prop would have to be threaded through every intermediate
 * component, and the innermost provider is the correct answer at every
 * depth.
 *
 * <p><b>Absent means the project root</b>, and that is the honest default
 * rather than a fallback: chat messages, inbox items and search hits
 * belong to no document, so there is nothing for a relative reference to
 * be relative to.
 *
 * <p>Lives in its own module for the same reason as
 * {@link ./vanceLinkHandler} — both {@link MarkdownView} and
 * {@link EmbeddedKindBox} read it, and the latter is imported by the
 * former.
 */
import { computed, inject, provide, type ComputedRef, type InjectionKey, type Ref } from 'vue';

/** Path of the document the surrounding content belongs to, or ''. */
export const DOCUMENT_REFERRER_KEY: InjectionKey<Readonly<Ref<string>>> =
  Symbol('vance-document-referrer');

/**
 * Declare the document whose content this subtree renders. Takes a ref so
 * a tab that switches documents moves its descendants with it.
 */
export function provideDocumentReferrer(path: Readonly<Ref<string>>): void {
  provide(DOCUMENT_REFERRER_KEY, path);
}

/** The current document path, '' where none was declared. */
export function useDocumentReferrer(): ComputedRef<string> {
  const injected = inject(DOCUMENT_REFERRER_KEY, null);
  return computed(() => injected?.value ?? '');
}
