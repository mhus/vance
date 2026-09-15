// Barrel for the designer addon's client surface. Consumers
// (vance-face today, federated remotes in the future) import the
// app component, REST helpers and wire-contract DTOs from here.

export { default as DesignerApp } from './DesignerApp.vue';
export {
  createDesign,
  createPreviewSession,
  deleteDesign,
  designContentUrl,
  getDesigner,
  reorderDesigns,
  updateDesignMeta,
} from './api';
export type { DesignInfo } from './generated/designer/DesignInfo';
export type { DesignerPreviewSession } from './generated/designer/DesignerPreviewSession';
export type { DesignerView } from './generated/designer/DesignerView';
