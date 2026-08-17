// Barrel for the store addon's client surface.

export { default as StoreArea } from './StoreArea.vue';
export { loadOverview, connect, disconnect, install } from './api';
export type {
  EntryState,
  KitOperationResult,
  StoreConnection,
  StoreEntry,
  StoreSourceView,
} from './types';
