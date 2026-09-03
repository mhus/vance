// Vance UI primitives — the V* family. The only workspace package
// allowed to use DaisyUI utility classes (btn, input, alert, card,
// dialog, ...) directly. Editors and addons compose their views from
// these components. See specification/web-ui.md §7.3.

import { defineAsyncComponent } from 'vue';

export { accentColorDotClass } from './accentColor';

// CodeEditor is exported LAZILY, and that is a bundling decision, not a
// stylistic one. This is a barrel: an entry that imports `VButton` from it
// pulls the whole module graph behind every other export into the same chunk.
// CodeEditor's graph is CodeMirror 6 plus a dozen lezer grammars (SQL, Python,
// Java, HTML, CSS, YAML, JS, Markdown) — measured at roughly two thirds of the
// 913 KB shared chunk that every page preloaded, on pages like scopes.html and
// users.html that contain no editor at all.
//
// Behind `defineAsyncComponent` the barrel holds only a thunk, so the grammars
// move into their own chunk and are fetched by the three surfaces that
// actually render source (Cortex's code mode, the workspace file viewer,
// compose output). Consumers keep importing it from here unchanged.
//
// Safe because nothing takes a template ref to it or names
// `InstanceType<typeof CodeEditor>` — it is driven entirely by props and
// events. Should that change, this export has to go back to being eager, or
// the ref has to be unwrapped explicitly.
export const CodeEditor = defineAsyncComponent(() => import('./CodeEditor.vue'));
// TYPE only, deliberately. `followUpExtension` / `dismissFollowUp` are
// CodeMirror extensions — re-exporting the *values* here dragged
// @codemirror/view and @codemirror/state (215 KB) back into the eager barrel
// chunk that lazifying CodeEditor above had just emptied, and for nobody:
// their only consumer is CodeEditor.vue, which imports them straight from
// './followUpExtension'. Outside this package only the type is used
// (DocumentTabShell builds the options object), and types cost no bytes.
export type { FollowUpExtensionOptions } from './followUpExtension';
// Live-WS Vue composables. Vue-bound, so they live here rather than in the
// platform-neutral @vance/shared (which must stay Vue-free for RN/Electron).
export * from './cortexLink';
export * from './useAppEntry';
export * from './useApplicationPicker';
export * from './useLinkPickerHost';
export * from './vanceUri';
export * from './useDocumentPrefixReaction';
export * from './usePointers';
// Translation access for bundles without vue-i18n on their dependency list —
// every addon remote, and this package itself.
export * from './useT';
export { default as FormFields } from './FormFields.vue';
export type { FormValue, FormValueObject } from './FormFields.vue';
export { default as VAlert } from './VAlert.vue';
export { default as VBackButton } from './VBackButton.vue';
export { default as VBadge } from './VBadge.vue';
export { default as VButton } from './VButton.vue';
export { default as VCard } from './VCard.vue';
export { default as VDropdown } from './VDropdown.vue';
export { default as VRange } from './VRange.vue';
export { default as VCheckbox } from './VCheckbox.vue';
export { default as VToggle } from './VToggle.vue';
export { default as VColorPicker } from './VColorPicker.vue';
export { default as VDataList } from './VDataList.vue';
export { default as VEmojiPicker } from './VEmojiPicker.vue';
export { default as VEmptyState } from './VEmptyState.vue';
export { default as VFileInput } from './VFileInput.vue';
export { default as VInput } from './VInput.vue';
export { default as VLinkPicker } from './VLinkPicker.vue';
export { default as VModal } from './VModal.vue';
export { default as VPagination } from './VPagination.vue';
export { default as VSelect } from './VSelect.vue';
export { default as VShareButton } from './VShareButton.vue';
export type { ShareSubjectInput } from './VShareButton.vue';
export { default as VSideTabs } from './VSideTabs.vue';
export type { SideTab } from './VSideTabs.vue';
export { default as VTagEditor } from './VTagEditor.vue';
export { default as VTextarea } from './VTextarea.vue';
