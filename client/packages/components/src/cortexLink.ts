/**
 * Builder for a Cortex deep link — "open this document in a new Cortex tab".
 *
 * Three addons hand-rolled the same template string (binder, canvas, the
 * common desktop). That was harmless while the link was two params; it stops
 * being harmless with {@link CortexLinkTarget.entry}, whose `<docId>:<handle>`
 * shape a caller can get subtly wrong (an unencoded handle silently truncates
 * at the first `:` or `,`). One producer, so the grammar is written once.
 *
 * The *authority* on the param grammar stays `cortexUrl.ts` in `@vance/face`,
 * which reads and writes the full view. This only produces the entry-point
 * form: one document, optionally one place inside it. Anything richer (tab
 * sets, chat binding) is Cortex's own business and does not travel in links.
 */

export interface CortexLinkTarget {
  /** Project name (not id) — the `project` param Cortex boots from. */
  project: string;
  /** Document id of the tab to open. */
  documentId: string;
  /**
   * Optional place *inside* the document, for application manifests: the
   * workbook page, the canvas board. Opaque app-owned handle; percent-encoded
   * here so `:` and `,` survive (see planning/inter-links.md §1).
   */
  entry?: string | null;
}

/** `/cortex?project=…&doc=…[&entry=…]` */
export function cortexDeepLink(target: CortexLinkTarget): string {
  const params = new URLSearchParams();
  params.set('project', target.project);
  params.set('doc', target.documentId);
  if (target.entry) {
    params.set('entry', `${target.documentId}:${encodeURIComponent(target.entry)}`);
  }
  return `/cortex?${params}`;
}
