// Barrel for the GTD addon's client surface — the mount wrapper for the
// application:gtd kind plus the GTD REST helpers.

export { default as GtdAppKind } from './GtdAppKind.vue';
export {
  scanGtd,
  getGtdAction,
  captureGtd,
  createGtdAction,
  patchGtdAction,
  moveGtdAction,
  deleteGtdAction,
  searchGtd,
  rebuildGtd,
} from './api';
export type { GtdView } from './generated/gtd/GtdView';
export type { GtdActionView } from './generated/gtd/GtdActionView';
export type { GtdActionContentView } from './generated/gtd/GtdActionContentView';
export type { GtdBucketView } from './generated/gtd/GtdBucketView';
