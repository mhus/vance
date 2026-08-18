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

/** What a buyer must agree to before a paid order is accepted. */
export interface WithdrawalNotice {
  required: boolean;
  /** Which wording is in force. Null when the notice is switched off. */
  version?: string | null;
}

// ──────────────────── developer ────────────────────

export type VendorStatus = 'PENDING' | 'APPROVED' | 'REJECTED';

/** What somebody accepts to become a vendor. */
export interface VendorTerms {
  version: string;
  text: string;
}

/** What the store keeps of a sale — shown before anything is priced. */
export interface StoreFees {
  percent: number;
  minimumFeeCents: number;
  minimumPriceCents: number;
}

export interface Vendor {
  name: string;
  displayName: string;
  homepage?: string | null;
  status: VendorStatus;
  termsVersion?: string | null;
  rejectionReason?: string | null;
}

/** One step of a release request. This is where a refusal is read. */
export interface ReleaseRound {
  no: number;
  at?: string | null;
  source: 'VENDOR' | 'OPERATOR' | 'MECHANICAL' | 'AI';
  verdict: 'SUBMITTED' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN';
  actor?: string | null;
  message?: string | null;
}

export interface ReleaseRequest {
  requestId: string;
  vendorName: string;
  kitId: string;
  version: string;
  status: 'OPEN' | 'PUBLISHED' | 'REJECTED' | 'WITHDRAWN';
  updatedAt?: string | null;
  rounds: ReleaseRound[];
}

/** A catalogue entry as its own vendor sees it. */
export interface VendorKit {
  vendorName: string;
  kitId: string;
  displayName: string;
  description?: string | null;
  priceCents: number;
  currency?: string | null;
  version?: string | null;
}

export interface DeveloperView {
  sourceId: string;
  connected: boolean;
  terms?: VendorTerms | null;
  fees?: StoreFees | null;
  vendors: Vendor[];
  kits: VendorKit[];
  requests: ReleaseRequest[];
  /** Why the store could not be asked. */
  problem?: string | null;
}

// ──────────────────── operator ────────────────────

/** A release as the operator's queue shows it. */
export interface QueuedRelease {
  vendorName: string;
  kitId: string;
  version: string;
  status: string;
  submittedAt?: string | null;
  rejectionReason?: string | null;
}

export interface OperatorView {
  pendingVendors: Vendor[];
  submittedReleases: QueuedRelease[];
}

/**
 * Which surfaces this user gets.
 *
 * The operator area grants nothing — the store refuses anyone who is not
 * in its own operator list — but a visible button puzzles everyone it does
 * not belong to and invites the rest to try it.
 */
export interface Surfaces {
  /** Source ids this brain is set up to operate (`store.operator.<id>`). */
  operatorSources: string[];
}
