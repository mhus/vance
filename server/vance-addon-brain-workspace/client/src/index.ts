// Barrel for the workspace addon's client surface.

export { default as WorkspaceAppKind } from './WorkspaceAppKind.vue';
export { scanWorkspace, rebuildWorkspace } from './api';
export type { WorkspaceView } from './generated/workspace/WorkspaceView';
export type { WorkspacePageView } from './generated/workspace/WorkspacePageView';
export type { WorkspaceRebuildResponse } from './generated/workspace/WorkspaceRebuildResponse';
