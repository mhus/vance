// Barrel for the workbook addon's client surface — mount wrappers for
// both kinds (canvas + application:workbook), plus the workbook REST
// helpers. The block-editor implementation itself lives in
// @vance/block-editor (shared package); WorkPageKind imports it from
// there.

export { default as WorkPageKind } from './WorkPageKind.vue';
export { default as WorkbookAppKind } from './WorkbookAppKind.vue';
export { scanWorkbook, rebuildWorkbook, createWorkbookPage } from './api';
export type { WorkbookView } from './generated/workbook/WorkbookView';
export type { WorkbookPageView } from './generated/workbook/WorkbookPageView';
export type { WorkbookRebuildResponse } from './generated/workbook/WorkbookRebuildResponse';
