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
  | { kind: 'image'; alt: string; src: string }
  | { kind: 'table'; headers: string[]; rows: string[][] }
  | { kind: 'callout'; severity: string; title: string | null; body: string }
  | { kind: 'toggle'; summary: string; body: string }
  | { kind: 'dataview'; source: string }
  | { kind: 'link-card'; href: string; title: string | null; description: string | null }
  | { kind: 'unknown-fence'; info: string; body: string };

export interface TodoItem {
  checked: boolean;
  text: string;
}

export interface CanvasDocument {
  title: string | null;
  description: string | null;
  blocks: Block[];
}
