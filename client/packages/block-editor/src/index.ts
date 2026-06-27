// Public API of @vance/block-editor — Tiptap-based Notion-style block
// editor with Markdown-Superset codec. The host (canvas addon, workspace
// addon, …) imports the editor component plus the codec functions; the
// markdown grammar is a single source of truth shared across consumers.

export { default as CanvasEditor } from './CanvasEditor.vue';
export { default as SlashMenu } from './SlashMenu.vue';

export { parse, parseDocument } from './markdown/parser';
export { serialize, serializeDocument } from './markdown/serializer';
export { blocksToContent, contentToBlocks } from './markdown/proseMirror';
export type { Block, CanvasDocument, TodoItem } from './markdown/blocks';

export {
  VanceCallout,
  VanceToggle,
  VanceLink,
  VanceDataview,
  VanceUnknownFence,
} from './extensions';
