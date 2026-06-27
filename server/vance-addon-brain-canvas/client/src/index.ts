// Barrel for the canvas addon's client surface.

export { default as CanvasEditor } from './CanvasEditor.vue';
export { default as CanvasKind } from './CanvasKind.vue';
export { parse, parseDocument } from './markdown/parser';
export { serialize, serializeDocument } from './markdown/serializer';
export type { Block, CanvasDocument, TodoItem } from './markdown/blocks';
