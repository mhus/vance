// Barrel for the store addon's client surface.

export { default as StoreArea } from './StoreArea.vue';
export {
  loadOverview,
  connect,
  disconnect,
  install,
  loadReviews,
  submitReview,
} from './api';
export type {
  EntryState,
  KitOperationResult,
  StoreConnection,
  StoreEntry,
  StoreReview,
  StoreSourceView,
} from './types';
