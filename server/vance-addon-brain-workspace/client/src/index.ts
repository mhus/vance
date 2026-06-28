// Barrel for the workspace addon's client surface — mount wrappers for
// both kinds (canvas + application:workspace), plus the workspace REST
// helpers. The block-editor implementation itself lives in
// @vance/block-editor (shared package); WorkPageKind imports it from
// there.

export { default as WorkPageKind } from './WorkPageKind.vue';
export { default as WorkspaceAppKind } from './WorkspaceAppKind.vue';
export { scanWorkspace, rebuildWorkspace, createWorkspacePage } from './api';
export type { WorkspaceView } from './generated/workspace/WorkspaceView';
export type { WorkspacePageView } from './generated/workspace/WorkspacePageView';
export type { WorkspaceRebuildResponse } from './generated/workspace/WorkspaceRebuildResponse';
