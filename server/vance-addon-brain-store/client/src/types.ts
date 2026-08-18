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
  /** What the vendor says this kit is for — free text, normalised. */
  topics: string[];
  /** What its newest published version contains — derived at the store. */
  contains: string[];
  state: EntryState;
}

export interface StoreSourceView {
  sourceId: string;
  /** What a person calls this store. The url lives in the profile. */
  title: string;
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
  /** Null while a text waits for moderation — the star still counts. */
  text?: string | null;
  /** Which version this opinion is about. */
  version?: string | null;
  majorVersion?: number | null;
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
  /** Which version landed — what the row promised before the click. */
  version?: string;
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
  topics: string[];
}

/** Where one handle stands on publishing — see kit-store §3 S20. */
export interface Publishing {
  vendorName: string;
  /** NOT_REQUIRED · VALID · GRACE · EXPIRED */
  standing: 'NOT_REQUIRED' | 'VALID' | 'GRACE' | 'EXPIRED';
  paidUntil?: string | null;
  renewalPriceCents: number;
  currency?: string | null;
  mayCreateKits: boolean;
  mayPublishPaid: boolean;
}

export interface DeveloperView {
  sourceId: string;
  connected: boolean;
  terms?: VendorTerms | null;
  fees?: StoreFees | null;
  vendors: Vendor[];
  kits: VendorKit[];
  requests: ReleaseRequest[];
  publishing: Publishing[];
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
  /** Source ids where this account is an operator — the store's answer. */
  operatorSources: string[];
  /** Source ids where it has a vendor profile — the developer role. */
  developerSources: string[];
}

/**
 * One store as the profile screen shows it.
 *
 * The address and the reachability live here and not in the store area:
 * somebody browsing kits picks by the name of the place, and an error they
 * cannot act on from there is furniture.
 */
export interface Connection {
  sourceId: string;
  title: string;
  url: string;
  reachable: boolean;
  problem?: string | null;
  accountId?: string | null;
  operator: boolean;
  developer: boolean;
}
