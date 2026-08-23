import { brainFetch, brainFetchBlob } from '@vance/shared';
import type {
  Connection,
  CreditNote,
  DeveloperView,
  KitOperationResult,
  OperatorView,
  Receipt,
  ReleaseRequest,
  StoreConnection,
  MoneyView,
  Payout,
  ReconcileResult,
  RefundResult,
  SaleRow,
  StoreOrder,
  TaxReport,
  Unclassified,
  VendorMoneyView,
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
 * Asks for the store password, unlike most of what is here: this
 * installation's link is taken for reading and managing, but not for
 * spending money or entering an agreement. The brain uses the password once
 * and closes the session.
 */
export async function buy(
  projectId: string,
  sourceId: string,
  vendor: string,
  kitId: string,
  email: string,
  password: string,
  billingCountry: string,
  vatId?: string,
  withdrawalNoticeVersion?: string,
): Promise<StoreOrder> {
  return brainFetch<StoreOrder>('POST', `${base(projectId)}/buy`, {
    body: {
      sourceId, vendor, kitId, email, password,
      billingCountry, vatId, withdrawalNoticeVersion,
    },
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
/**
 * Buy one more publishing period for one handle.
 *
 * Takes a country and an optional VAT id for the same reason buying a kit
 * does: it is a sale, the store taxes it where the buyer is, and a renewal
 * that recorded neither would sit in the unclassified list for good.
 */
export async function renewPublishing(
  projectId: string,
  sourceId: string,
  vendorName: string,
  email: string,
  password: string,
  billingCountry: string,
  vatId?: string,
): Promise<StoreOrder> {
  return brainFetch<StoreOrder>('POST', `${base(projectId)}/developer/renew`, {
    body: { sourceId, vendorName, email, password, billingCountry, vatId: vatId || undefined },
  });
}

// ──────────────────── money ────────────────────

export async function loadMoney(projectId: string, sourceId: string): Promise<MoneyView> {
  const query = new URLSearchParams({ sourceId }).toString();
  return brainFetch<MoneyView>('GET', `${base(projectId)}/operator/money?${query}`);
}

export async function payVendor(
  projectId: string, sourceId: string, vendorName: string,
): Promise<Payout> {
  // Path segment encoded, like every other call here. A '/' in a name
  // would otherwise move this request from "pay" to "release".
  const query = new URLSearchParams({ sourceId }).toString();
  return brainFetch<Payout>(
    'POST',
    `${base(projectId)}/operator/payouts/${encodeURIComponent(vendorName)}?${query}`);
}

export async function releasePayout(
  projectId: string, sourceId: string, payoutName: string,
): Promise<Payout> {
  const query = new URLSearchParams({ sourceId }).toString();
  return brainFetch<Payout>(
    'POST',
    `${base(projectId)}/operator/payouts/${encodeURIComponent(payoutName)}/release?${query}`);
}

export async function reconcilePayouts(
  projectId: string, sourceId: string,
): Promise<ReconcileResult> {
  const query = new URLSearchParams({ sourceId }).toString();
  return brainFetch<ReconcileResult>(
    'POST', `${base(projectId)}/operator/payouts-reconcile?${query}`);
}

export async function refundOrder(
  projectId: string, sourceId: string, orderName: string,
  reason: string, alreadyReturned: boolean,
): Promise<RefundResult> {
  return brainFetch<RefundResult>('POST', `${base(projectId)}/operator/refund`, {
    body: { sourceId, orderName, reason: reason || undefined, alreadyReturned },
  });
}

export async function loadUnclassified(
  projectId: string, sourceId: string,
): Promise<Unclassified> {
  const query = new URLSearchParams({ sourceId }).toString();
  return brainFetch<Unclassified>('GET', `${base(projectId)}/operator/unclassified?${query}`);
}

/** The country is sent; the rate is the store's to derive. */
export async function classifyOrder(
  projectId: string, sourceId: string, orderName: string,
  billingCountry: string, vatId?: string,
): Promise<SaleRow> {
  return brainFetch<SaleRow>('POST', `${base(projectId)}/operator/classify`, {
    body: { sourceId, orderName, billingCountry, vatId: vatId || undefined },
  });
}

export async function reissueCreditNote(
  projectId: string, sourceId: string, payoutName: string,
): Promise<CreditNote> {
  return brainFetch<CreditNote>(
    'POST', `${base(projectId)}/operator/credit-notes/reissue`, {
      body: { sourceId, payoutName },
    });
}

/**
 * Open a rendered document in a new tab.
 *
 * <p>Through the brain rather than as a plain link: the store wants a link
 * token the browser does not hold, and putting one in a URL would be a
 * credential in the address bar and in the history.
 */
async function openPdf(path: string): Promise<void> {
  const { blob, filename } = await brainFetchBlob(path);
  const url = URL.createObjectURL(blob);
  // The name the store suggested, carried through: a blob URL saves as a
  // UUID otherwise, and an invoice filed under a random name is an invoice
  // nobody finds again. An anchor rather than window.open because only the
  // anchor takes a download name.
  if (filename) {
    const link = document.createElement('a');
    link.href = url;
    link.download = filename;
    link.target = '_blank';
    link.rel = 'noopener';
    link.click();
  } else {
    window.open(url, '_blank', 'noopener');
  }
  // Revoked late: revoking straight away can beat the new tab to the file.
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}

export async function loadReceipts(
  projectId: string, sourceId: string,
): Promise<Receipt[]> {
  const query = new URLSearchParams({ sourceId }).toString();
  return brainFetch<Receipt[]>('GET', `${base(projectId)}/documents/invoices?${query}`);
}

export async function openInvoicePdf(
  projectId: string, sourceId: string, orderName: string,
): Promise<void> {
  const query = new URLSearchParams({ sourceId, orderName }).toString();
  return openPdf(`${base(projectId)}/documents/invoice?${query}`);
}

export async function openCreditNotePdf(
  projectId: string, sourceId: string, vendorName: string, number: string,
): Promise<void> {
  const query = new URLSearchParams({ sourceId, vendorName, number }).toString();
  return openPdf(`${base(projectId)}/documents/credit-note?${query}`);
}

export async function openTaxReportPdf(
  projectId: string, sourceId: string, from: string, to: string,
): Promise<void> {
  const query = new URLSearchParams({ sourceId, from, to }).toString();
  return openPdf(`${base(projectId)}/documents/tax-report?${query}`);
}

export async function loadTaxReport(
  projectId: string, sourceId: string, from: string, to: string,
): Promise<TaxReport> {
  const query = new URLSearchParams({ sourceId, from, to }).toString();
  return brainFetch<TaxReport>('GET', `${base(projectId)}/operator/tax-report?${query}`);
}

export async function claimDomain(
  projectId: string, sourceId: string, vendorName: string, domain: string,
): Promise<Vendor> {
  return brainFetch<Vendor>('POST', `${base(projectId)}/developer/domain`, {
    body: { sourceId, vendorName, domain },
  });
}

export async function verifyDomain(
  projectId: string, sourceId: string, vendorName: string,
): Promise<Vendor> {
  return brainFetch<Vendor>('POST', `${base(projectId)}/developer/domain/verify`, {
    body: { sourceId, vendorName },
  });
}

export async function loadVendorMoney(
  projectId: string, sourceId: string, vendorName: string,
): Promise<VendorMoneyView> {
  const query = new URLSearchParams({ sourceId, vendorName }).toString();
  return brainFetch<VendorMoneyView>('GET', `${base(projectId)}/developer/money?${query}`);
}

export async function setPayoutAccount(
  projectId: string, sourceId: string, vendorName: string, type: string, handle: string,
  holderName?: string, country?: string, vatId?: string,
): Promise<Vendor> {
  return brainFetch<Vendor>('POST', `${base(projectId)}/developer/payout-account`, {
    body: { sourceId, vendorName, type, handle, holderName, country, vatId },
  });
}

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
  topics?: string[],
): Promise<VendorKit> {
  return brainFetch<VendorKit>('POST', `${base(projectId)}/developer/kits`, {
    body: { sourceId, vendor, kitId, displayName, description, priceCents, currency, topics },
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
 * No sign-in: whether this account may operate is the store's answer, and
 * the brain asks it with the link this installation already holds.
 */
export async function loadOperatorQueue(
  projectId: string,
  sourceId: string,
): Promise<OperatorView> {
  const query = new URLSearchParams({ sourceId }).toString();
  return brainFetch<OperatorView>('GET', `${base(projectId)}/operator/queue?${query}`);
}

/** The switch: approve or refuse a vendor or a release. */
export async function decide(
  projectId: string,
  decision: 'approve-vendor' | 'reject-vendor' | 'approve-release' | 'reject-release',
  body: {
    sourceId: string;
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

/** Every configured store, with roles and reachability — for the profile. */
export async function loadConnections(projectId: string): Promise<Connection[]> {
  return brainFetch<Connection[]>('GET', `${base(projectId)}/connections`);
}
