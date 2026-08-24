/**
 * Host bridge for an application's sub-position — which page, board or column
 * is open *inside* an app tab.
 *
 * Workbook and Wiki each used to write a bare `?page=` straight onto the
 * location. With two such tabs open in Cortex they fought over one param and
 * the second one won. The fix is not a better param name but an owner: the
 * host keys the value by the app tab's document id, exactly as it already
 * scopes `vance:report-active-subdoc` by `appDocId`
 * (planning/inter-links.md §5.2).
 *
 * The app keeps deciding what a handle *means* — a page id in Workbook and
 * Canvasbook, a space-qualified slug in Wiki. This composable only moves it
 * between the app and whoever hosts it.
 *
 * **Without a host** (mounted outside Cortex) both sides degrade to nothing:
 * `entry` stays null and `report` is a no-op, so the app works, it just has no
 * URL memory. That is the same shape as every other `vance:`-injection here,
 * and it is why the app must treat `entry` as a *suggestion* and keep its own
 * notion of the active place.
 */
import { computed, inject, toValue, type ComputedRef, type MaybeRefOrGetter, type Ref } from 'vue';

/** Injection key for the host's reactive `docId → handle` map. */
export const APP_ENTRY_KEY = 'vance:app-entry';
/** Injection key for the host's report callback. */
export const REPORT_APP_ENTRY_KEY = 'vance:report-app-entry';

export type AppEntryHistory = 'push' | 'replace';

export interface AppEntryReport {
  appDocId: string;
  entry: string | null;
  history?: AppEntryHistory;
}

export type ReportAppEntry = (report: AppEntryReport) => void;

export interface AppEntryApi {
  /** The place the host wants open, or `null`. Reactive — a link click while the tab is already open changes it. */
  entry: ComputedRef<string | null>;
  /** True when a host is listening; lets an app skip URL-specific work. */
  hosted: boolean;
  /**
   * Tell the host which place is open now.
   *
   * @param history `push` for a navigation the user made (back should undo it),
   *                `replace` for restoring or normalising. Defaults to `replace`
   *                because the silent cases outnumber the navigations.
   */
  report(handle: string | null, history?: AppEntryHistory): void;
}

/**
 * @param appDocId the app tab's own document id — the key the host scopes by.
 *                 May be undefined while the document is still loading; the
 *                 composable then reads null and reports nothing.
 */
export function useAppEntry(appDocId: MaybeRefOrGetter<string | undefined | null>): AppEntryApi {
  const entries = inject<Ref<Record<string, string>> | null>(APP_ENTRY_KEY, null);
  const report = inject<ReportAppEntry | null>(REPORT_APP_ENTRY_KEY, null);

  return {
    entry: computed(() => {
      const id = toValue(appDocId);
      if (!id || !entries) return null;
      return entries.value[id] ?? null;
    }),
    hosted: !!report,
    report(handle: string | null, history: AppEntryHistory = 'replace') {
      const id = toValue(appDocId);
      if (!id || !report) return;
      report({ appDocId: id, entry: handle, history });
    },
  };
}
