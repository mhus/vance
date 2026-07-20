import { ref } from 'vue';
import type { JSONContent } from '@tiptap/core';

/**
 * Module-scoped block clipboard for the block-handle menu's Copy/Paste.
 * Module scope (not per-editor-instance) so a block copied on one page
 * survives the editor remount on a workbook page switch and can be pasted on
 * another page within the same bundle.
 */
export const blockClipboard = ref<JSONContent | null>(null);
