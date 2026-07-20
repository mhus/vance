import { ref } from 'vue';
import type { JSONContent } from '@tiptap/core';

/**
 * Shared block clipboard for the block-handle menu's Copy/Paste, persisted in
 * localStorage so Copy/Paste works across pages, editor remounts, reloads and
 * same-origin tabs. In-memory ref for reactivity (the menu's Paste-disabled
 * state); localStorage is the durable backing + cross-tab channel. Degrades to
 * in-memory-only if localStorage is unavailable (private mode / quota).
 */
const KEY = 'vance:block-clipboard';

function read(): JSONContent | null {
  try {
    const raw = localStorage.getItem(KEY);
    return raw ? (JSON.parse(raw) as JSONContent) : null;
  } catch {
    return null;
  }
}

export const blockClipboard = ref<JSONContent | null>(read());

/** Set the shared clipboard (in-memory + localStorage). Pass null to clear. */
export function setBlockClipboard(node: JSONContent | null): void {
  blockClipboard.value = node;
  try {
    if (node == null) localStorage.removeItem(KEY);
    else localStorage.setItem(KEY, JSON.stringify(node));
  } catch {
    // localStorage unavailable — the in-memory ref still serves this tab.
  }
}

// Another same-origin tab copied a block → reflect it here (keeps the menu's
// Paste-enabled state + pasted content in sync without a reload).
if (typeof window !== 'undefined') {
  window.addEventListener('storage', (e) => {
    if (e.key === KEY) blockClipboard.value = read();
  });
}
