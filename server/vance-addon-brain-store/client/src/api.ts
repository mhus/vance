import { brainFetch } from '@vance/shared';
import type {
  KitOperationResult,
  StoreConnection,
  StoreOrder,
  StoreReview,
  StoreSourceView,
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
): Promise<StoreOrder> {
  return brainFetch<StoreOrder>('POST', `${base(projectId)}/buy`, {
    body: { sourceId, vendor, kitId, email, password },
  });
}
