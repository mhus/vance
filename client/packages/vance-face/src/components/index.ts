// Composite UI building blocks that depend on vance-face internals
// (composables, stores, kind registry). The V* primitives live in
// @vance/components and are re-exported here so existing
// `import { ... } from '@components'` paths in vance-face keep working.
// Addons should import V* primitives directly from @vance/components.

export * from '@vance/components';

export { default as CodeEditor } from './CodeEditor.vue';
export { default as EditorShell } from './EditorShell.vue';
export type { Crumb, FocusZone } from './EditorShell.vue';
export { default as MarkdownView } from './MarkdownView.vue';
export { default as ProjectListSidebar } from './ProjectListSidebar.vue';
export type { PickerNode } from './ProjectListSidebar.vue';
export { default as SessionHeader } from './SessionHeader.vue';
export { default as FormFields } from './FormFields.vue';
export type { FormValue, FormValueObject } from './FormFields.vue';
export { default as SettingFormView } from './SettingFormView.vue';
