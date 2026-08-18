import { brainFetch } from '@vance/shared';
import type {
  DeveloperView,
  KitOperationResult,
  OperatorView,
  ReleaseRequest,
  StoreConnection,
  StoreOrder,
  StoreReview,
  StoreSourceView,
  Surfaces,
  Vendor,
  VendorKit,
  WithdrawalNotice,
} from './types';

function base(projectId: string): string {
  return `addon/store/${encodeURIComponent(projectId)}`;
}

/** The four lists, per configured library. */
export async function loadOverview(projectId: string): Promise<StoreSourceView[]> {
  return brainFetch<StoreSourceView[]>('GET', `${base(projectId)}/overview`);
}

/**
 * Sign in to a store.
 *
 * The password goes to the brain, which uses it once against the store and
 * discards it. No store credential ever comes back here — what returns is
 * an account id, which is not a secret.
 */
export async function connect(
  projectId: string,
  sourceId: string,
  email: string,
  password: string,
  label?: string,
): Promise<StoreConnection> {
  return brainFetch<StoreConnection>('POST', `${base(projectId)}/connect`, {
    body: { sourceId, email, password, label },
  });
}

/** Forget the credential here. The link at the store survives. */
export async function disconnect(
  projectId: string,
  sourceId: string,
): Promise<StoreConnection> {
  return brainFetch<StoreConnection>('POST', `${base(projectId)}/disconnect`, {
    body: { sourceId },
  });
}

/** Install, or update when it is already installed — the brain decides which. */
export async function install(
  projectId: string,
  sourceId: string,
  path: string,
): Promise<KitOperationResult> {
  return brainFetch<KitOperationResult>('POST', `${base(projectId)}/install`, {
    body: { sourceId, path },
  });
}

/** The reviews of one kit whose text an operator has cleared. */
export async function loadReviews(
  projectId: string,
  sourceId: string,
  vendor: string,
  kitId: string,
): Promise<StoreReview[]> {
  const query = new URLSearchParams({ sourceId, vendor, kitId }).toString();
  return brainFetch<StoreReview[]>('GET', `${base(projectId)}/reviews?${query}`);
}

/**
 * Leave or change a review.
 *
 * Authenticated at the store by this installation's link token, which the
 * brain holds and the browser never sees.
 */
export async function submitReview(
  projectId: string,
  sourceId: string,
  vendor: string,
  kitId: string,
  stars: number,
  text?: string,
): Promise<StoreReview> {
  return brainFetch<StoreReview>('POST', `${base(projectId)}/review`, {
    body: { sourceId, vendor, kitId, stars, text },
  });
}

/**
 * Buy a kit.
 *
 * Asks for the store password, unlike everything else here: the store
 * accepts this installation's link token for leaving a review and for
 * nothing that spends money. The brain uses it once and closes the session.
 */
export async function buy(
  projectId: string,
  sourceId: string,
  vendor: string,
  kitId: string,
  email: string,
  password: string,
  withdrawalNoticeVersion?: string,
): Promise<StoreOrder> {
  return brainFetch<StoreOrder>('POST', `${base(projectId)}/buy`, {
    body: { sourceId, vendor, kitId, email, password, withdrawalNoticeVersion },
  });
}

/**
 * The withdrawal notice a store currently requires.
 *
 * Fetched before the buy form is shown, so the version the buyer confirms
 * is the one the store will accept.
 */
export async function loadWithdrawalNotice(
  projectId: string,
  sourceId: string,
): Promise<WithdrawalNotice> {
  const query = new URLSearchParams({ sourceId }).toString();
  return brainFetch<WithdrawalNotice>(
    'GET', `${base(projectId)}/withdrawal-notice?${query}`,
  );
}

// ──────────────────── developer ────────────────────

/**
 * The developer's own view of a store.
 *
 * The terms and the fees come back even when this installation is not
 * signed in: somebody deciding whether to sell here should not have to
 * sign up to find out what it costs.
 */
export async function loadDeveloper(
  projectId: string,
  sourceId: string,
): Promise<DeveloperView> {
  const query = new URLSearchParams({ sourceId }).toString();
  return brainFetch<DeveloperView>('GET', `${base(projectId)}/developer?${query}`);
}

/**
 * Apply to be a vendor.
 *
 * Asks for the store password, like buying does: accepting terms is a
 * decision by a person, and this installation's link token must not enter
 * an agreement on their behalf.
 */
export async function applyVendor(
  projectId: string,
  sourceId: string,
  email: string,
  password: string,
  name: string,
  displayName: string,
  termsVersion: string,
  homepage?: string,
): Promise<Vendor> {
  return brainFetch<Vendor>('POST', `${base(projectId)}/developer/apply`, {
    body: { sourceId, email, password, name, displayName, homepage, termsVersion },
  });
}

/** Add a catalogue entry under one's own vendor. */
export async function createKit(
  projectId: string,
  sourceId: string,
  vendor: string,
  kitId: string,
  displayName: string,
  description: string | undefined,
  priceCents: number,
  currency?: string,
): Promise<VendorKit> {
  return brainFetch<VendorKit>('POST', `${base(projectId)}/developer/kits`, {
    body: { sourceId, vendor, kitId, displayName, description, priceCents, currency },
  });
}

/**
 * Export this project and submit it as a version.
 *
 * The project has to be a kit source — the export says so in its own words
 * when it is not.
 */
export async function publish(
  projectId: string,
  sourceId: string,
  vendor: string,
  kitId: string,
  version: string,
  vaultPassword?: string,
): Promise<ReleaseRequest> {
  return brainFetch<ReleaseRequest>('POST', `${base(projectId)}/developer/publish`, {
    body: { sourceId, vendor, kitId, version, vaultPassword },
  });
}

// ──────────────────── operator ────────────────────

/**
 * The operator's queues.
 *
 * A POST although it reads: the operator surface takes a session, so this
 * carries a password, and a password in a query string ends up in logs and
 * in browser history.
 */
export async function loadOperatorQueue(
  projectId: string,
  sourceId: string,
  email: string,
  password: string,
): Promise<OperatorView> {
  return brainFetch<OperatorView>('POST', `${base(projectId)}/operator/queue`, {
    body: { sourceId, email, password },
  });
}

/** The switch: approve or refuse a vendor or a release. */
export async function decide(
  projectId: string,
  decision: 'approve-vendor' | 'reject-vendor' | 'approve-release' | 'reject-release',
  body: {
    sourceId: string;
    email: string;
    password: string;
    vendor?: string;
    kitId?: string;
    version?: string;
    reason?: string;
  },
): Promise<OperatorView> {
  return brainFetch<OperatorView>('POST', `${base(projectId)}/operator/${decision}`, { body });
}

/**
 * The projects of this tenant.
 *
 * Needed because publishing exports a *particular* project, and the addon
 * host mounts an area without telling it which one is open. Naming it in
 * the panel beats inferring it from how somebody arrived at the page.
 */
export async function loadProjects(): Promise<{ name: string; title?: string }[]> {
  const answer = await brainFetch<{ projects?: { name: string; title?: string }[] }>(
    'GET', 'projects',
  );
  return answer.projects ?? [];
}

/** Which stores this user is set up to operate. */
export async function loadSurfaces(projectId: string): Promise<Surfaces> {
  return brainFetch<Surfaces>('GET', `${base(projectId)}/surfaces`);
}
