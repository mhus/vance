// Barrel for the workspace addon's client surface — mount wrappers for
// both kinds (canvas + application:workspace), plus the workspace REST
// helpers. The block-editor implementation itself lives in
// @vance/block-editor (shared package); CanvasKind imports it from
// there.

export { default as CanvasKind } from './CanvasKind.vue';
export { default as WorkspaceAppKind } from './WorkspaceAppKind.vue';
export { scanWorkspace, rebuildWorkspace } from './api';
export type { WorkspaceView } from './generated/workspace/WorkspaceView';
export type { WorkspacePageView } from './generated/workspace/WorkspacePageView';
export type { WorkspaceRebuildResponse } from './generated/workspace/WorkspaceRebuildResponse';
