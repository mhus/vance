// Public API of @vance/block-editor — Tiptap-based Notion-style block
// editor with Markdown-Superset codec. The host (workspace addon, …)
// imports the editor component plus the codec functions; the markdown
// grammar (kind: workpage) is a single source of truth shared across
// consumers.

export { default as WorkPageEditor } from './WorkPageEditor.vue';
export { default as BlockView } from './BlockView.vue';
export { default as InlineRender } from './InlineRender.vue';
export { default as SlashMenu } from './SlashMenu.vue';

export { parse, parseDocument } from './markdown/parser';
export { serialize, serializeDocument } from './markdown/serializer';
export { blocksToContent, contentToBlocks } from './markdown/proseMirror';
export type { Block, WorkPageDocument, TodoItem } from './markdown/blocks';

export {
  VanceCallout,
  VanceToggle,
  VanceLink,
  VanceDataview,
  VanceUnknownFence,
} from './extensions';
