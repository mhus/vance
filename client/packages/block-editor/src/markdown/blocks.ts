// Block-tree primitives — TS mirror of de.mhus.vance.addon.brain.canvas.Block.
// Used by parser.ts and serializer.ts. The Tiptap editor projects these onto
// ProseMirror nodes via canvasToProseMirror() / proseMirrorToCanvas().

export type Block =
  | { kind: 'paragraph'; text: string }
  | { kind: 'heading'; level: 1 | 2 | 3; text: string }
  | { kind: 'bullet-list'; items: string[] }
  | { kind: 'numbered-list'; items: string[] }
  | { kind: 'todo'; items: TodoItem[] }
  | { kind: 'quote'; text: string }
  | { kind: 'code'; lang: string | null; code: string }
  | { kind: 'divider' }
  | { kind: 'image'; alt: string; src: string; width?: ImageWidth | null }
  | { kind: 'table'; headers: string[]; rows: string[][] }
  | { kind: 'callout'; severity: string; title: string | null; body: string }
  | { kind: 'toggle'; summary: string; body: string }
  | { kind: 'dataview'; source: string }
  | { kind: 'link-card'; href: string; title: string | null; description: string | null }
  | { kind: 'toc' }
  | {
      /**
       * Embedded reference to another Vance document. Rendered as a
       * kind-aware preview card (Refresh-on-hover, ⌘+click to open).
       * Spec: specification/inline-and-embedded-content.md §3 / §11.
       */
      kind: 'embed';
      uri: string;
    }
  | {
      kind: 'columns';
      /**
       * One slot per column. `blocks` is the column's content; `width`
       * is the relative fraction (0..1) of the container row. `null`
       * means "equal share of the remaining space" — when all widths
       * are null the columns are equally wide. Widths are not required
       * to sum to 1; the renderer normalises by total.
       */
      columns: Array<{ blocks: Block[]; width: number | null }>;
    }
  | { kind: 'unknown-fence'; info: string; body: string };

/**
 * Image-block width presets. `full` (default) takes the full content
 * width; `small`/`medium`/`large` render the image at a fixed fraction.
 * Persisted as a pipe-suffix in the markdown alt-text — e.g.
 * {@code ![My pic|small](url.png)} — so the document round-trips
 * losslessly without resorting to HTML.
 */
export type ImageWidth = 'small' | 'medium' | 'large' | 'full';

export const IMAGE_WIDTHS: readonly ImageWidth[] = ['small', 'medium', 'large', 'full'];

export interface TodoItem {
  checked: boolean;
  text: string;
}

export interface CanvasDocument {
  title: string | null;
  description: string | null;
  /**
   * Page icon — usually a single emoji ("📚") but any short string is
   * accepted. Rendered next to the title in the page header.
   */
  icon: string | null;
  /**
   * Cover image — relative document path under the workspace
   * ("assets/foo.png") or absolute URL. Rendered as a wide banner
   * above the page title.
   */
  cover: string | null;
  blocks: Block[];
}
