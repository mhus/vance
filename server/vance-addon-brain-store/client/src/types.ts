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
  averageStars: number;
  ratingCount: number;
  /** Smallest currency unit. Zero means free. */
  priceCents: number;
  currency?: string | null;
  licenseTermDays?: number | null;
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

/** A review whose text an operator has cleared. */
export interface StoreReview {
  reviewId: string;
  displayName?: string | null;
  stars: number;
  text?: string | null;
  createdAt?: string | null;
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

/** What an order came to. */
export interface StoreOrder {
  orderId: string;
  status: string;
  /** Where to go and pay. Null when there was nothing to pay. */
  redirectUrl?: string | null;
  failureReason?: string | null;
}
