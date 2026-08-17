/**
 * Wire shapes of the store addon's brain endpoints.
 *
 * <p>Hand-written rather than generated: these belong to the addon's own
 * controller, not to `vance-api`, so the TypeScript generator never sees
 * them.
 */

/** Where one kit stands from this installation's point of view. */
export type EntryState = 'OFFERED' | 'OWNED' | 'INSTALLED' | 'UPDATABLE';

export interface StoreEntry {
  sourceId: string;
  sourceUrl: string;
  /** `vendor/kitId` — how the kit is addressed inside its library. */
  path: string;
  vendor: string;
  kitId: string;
  displayName: string;
  description?: string | null;
  license?: string | null;
  homepage?: string | null;
  availableVersion?: string | null;
  installedVersion?: string | null;
  licenseExpiresAt?: string | null;
  downloadable: boolean;
  state: EntryState;
}

export interface StoreSourceView {
  sourceId: string;
  url: string;
  /** Which store account this user is signed in as, or null. */
  accountId?: string | null;
  reachable: boolean;
  /** Why the store could not be asked. Only set when `reachable` is false. */
  problem?: string | null;
  entries: StoreEntry[];
}

export interface StoreConnection {
  sourceId: string;
  accountId?: string | null;
}

export interface KitOperationResult {
  kitName?: string;
  kitId?: string;
  mode?: string;
  warnings?: string[];
}
